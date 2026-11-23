package com.retroremote.protocol

import android.content.SharedPreferences
import android.util.Base64
import java.io.*
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.security.*
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.*
import javax.net.ssl.*

/** Android TV Remote v2: pairing on 6467, authenticated command channel on 6466. */
class AtvIdentity private constructor(val key: PrivateKey, val cert: X509Certificate) {
    companion object {
        fun load(p: SharedPreferences): AtvIdentity {
            val k = p.getString("atv_key", null)
            val c = p.getString("atv_cert", null)
            if (k != null && c != null) return AtvIdentity(
                KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.decode(k, Base64.DEFAULT))),
                CertificateFactory.getInstance("X.509").generateCertificate(Base64.decode(c, Base64.DEFAULT).inputStream()) as X509Certificate
            )
            val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val cert = selfSigned(pair, "RetroRemote-" + UUID.randomUUID().toString())
            p.edit().putString("atv_key", Base64.encodeToString(pair.private.encoded, Base64.NO_WRAP))
                .putString("atv_cert", Base64.encodeToString(cert.encoded, Base64.NO_WRAP)).apply()
            return AtvIdentity(pair.private, cert)
        }
    }
}

private fun selfSigned(pair: KeyPair, cn: String): X509Certificate {
    fun tlv(tag: Int, b: ByteArray): ByteArray {
        val o = ByteArrayOutputStream(); o.write(tag)
        if (b.size < 128) o.write(b.size) else { o.write(0x81); o.write(b.size) }
        o.write(b); return o.toByteArray()
    }
    fun seq(vararg parts: ByteArray): ByteArray {
        val o = ByteArrayOutputStream(); parts.forEach { o.write(it) }; return tlv(0x30, o.toByteArray())
    }
    fun oid(s: String): ByteArray {
        val a=s.split(".").map { it.toInt() }; val o=ByteArrayOutputStream(); o.write(a[0]*40+a[1])
        a.drop(2).forEach { if(it<128)o.write(it) else {o.write((it ushr 7) or 128);o.write(it and 127)} }
        return tlv(6,o.toByteArray())
    }
    val alg=seq(oid("1.2.840.113549.1.1.11"),byteArrayOf(5,0))
    val name=seq(tlv(0x31,seq(oid("2.5.4.3"),tlv(0x0c,cn.toByteArray()))))
    fun date(ms:Long):ByteArray {
        val f=SimpleDateFormat("yyMMddHHmmss'Z'",Locale.US).apply { timeZone=TimeZone.getTimeZone("UTC") }
        return tlv(0x17,f.format(Date(ms)).toByteArray())
    }
    val now=System.currentTimeMillis()
    val tbs=seq(tlv(0xa0,tlv(2,byteArrayOf(2))),tlv(2,byteArrayOf(1,2,3,4)),alg,name,
        seq(date(now-86400000L),date(now+315360000000L)),name,pair.public.encoded)
    val signature=Signature.getInstance("SHA256withRSA").run { initSign(pair.private);update(tbs);sign() }
    return CertificateFactory.getInstance("X.509").generateCertificate(seq(tbs,alg,tlv(3,byteArrayOf(0)+signature)).inputStream()) as X509Certificate
}

private class W {
    private val o=ByteArrayOutputStream()
    private fun v(value:Long) { var n=value; while(n and -128L!=0L){o.write(((n and 127) or 128).toInt());n=n ushr 7};o.write(n.toInt()) }
    fun i(f:Int,n:Int)=apply { v((f shl 3).toLong());v(n.toLong()) }
    fun b(f:Int,a:ByteArray)=apply { v(((f shl 3) or 2).toLong());v(a.size.toLong());o.write(a) }
    fun s(f:Int,a:String)=b(f,a.toByteArray())
    fun m(f:Int,a:W)=b(f,a.data())
    fun data()=o.toByteArray()
}
private class R(private val a:ByteArray) {
    private var p=0
    private fun v():Long { var x=0L;var sh=0;while(p<a.size){val n=a[p++].toInt() and 255;x=x or ((n and 127).toLong() shl sh);if(n<128)return x;sh+=7;if(sh>=64)error("Malformed protobuf")};error("Truncated protobuf") }
    fun fields():Map<Int,List<Any>> { val out=linkedMapOf<Int,MutableList<Any>>()
        while(p<a.size){val tag=v().toInt();val id=tag ushr 3;val value:Any=when(tag and 7){
            0->v();2->{val n=v().toInt();require(n>=0&&n<=a.size-p);a.copyOfRange(p,p+n).also{p+=n}}
            1->{a.copyOfRange(p,p+8).also{p+=8}};5->{a.copyOfRange(p,p+4).also{p+=4}};else->error("Bad protobuf wire type")}
            out.getOrPut(id){mutableListOf()}.add(value)}
        return out
    }
}
private fun Map<Int,List<Any>>.num(id:Int)=(this[id]?.lastOrNull() as? Long)?.toInt() ?: 0
private fun Map<Int,List<Any>>.blob(id:Int)=this[id]?.lastOrNull() as? ByteArray
private fun Map<Int,List<Any>>.sub(id:Int)=blob(id)?.let { R(it).fields() }
private fun writeFrame(out:OutputStream,data:ByteArray) { var n=data.size;val b=ByteArrayOutputStream()
    while(n and -128!=0){b.write((n and 127) or 128);n=n ushr 7};b.write(n);b.write(data);out.write(b.toByteArray());out.flush() }
private fun readFrame(input:InputStream):ByteArray { var b=input.read();if(b<0)throw EOFException("TV disconnected")
    var size=b and 127;var sh=7;while(b and 128!=0){b=input.read();if(b<0||sh>28)error("Bad frame");size=size or ((b and 127) shl sh);sh+=7}
    require(size in 1..1048576);val data=ByteArray(size);var p=0
    while(p<size){val n=input.read(data,p,size-p);if(n<0)throw EOFException("Truncated frame");p+=n};return data }

private fun socket(host:String,port:Int,id:AtvIdentity,trust:X509TrustManager):SSLSocket {
    val km=object:X509ExtendedKeyManager(){
        override fun getClientAliases(k:String?,i:Array<out java.security.Principal>?)=arrayOf("rr")
        override fun chooseClientAlias(k:Array<out String>?,i:Array<out java.security.Principal>?,s:Socket?)="rr"
        override fun getCertificateChain(a:String?)=arrayOf(id.cert)
        override fun getPrivateKey(a:String?)=id.key
        override fun getServerAliases(k:String?,i:Array<out java.security.Principal>?)=arrayOf("rr")
        override fun chooseServerAlias(k:Array<out String>?,i:Array<out java.security.Principal>?,s:Socket?)="rr"
        override fun chooseEngineClientAlias(k:Array<out String>?,i:Array<out java.security.Principal>?,e:SSLEngine?)="rr"
        override fun chooseEngineServerAlias(k:String?,i:Array<out java.security.Principal>?,e:SSLEngine?)="rr"
    }
    val raw=Socket();raw.tcpNoDelay=true;raw.connect(InetSocketAddress(host,port),15000);raw.soTimeout=30000
    return try { val ctx=SSLContext.getInstance("TLS").apply{init(arrayOf(km),arrayOf(trust),SecureRandom())}
        (ctx.socketFactory.createSocket(raw,host,port,true) as SSLSocket).apply{useClientMode=true;startHandshake()}
    } catch(t:Throwable){raw.close();throw t}
}

class AtvPairing(private val host:String,private val identity:AtvIdentity):Closeable {
    private var s:SSLSocket?=null;private var input:InputStream?=null;private var output:OutputStream?=null
    private var tvCert:X509Certificate?=null
    val pin:String get()=tvCert?.let{shaPin(it)} ?: ""
    fun begin() {
        val tm=object:X509TrustManager {
            var cert:X509Certificate?=null
            override fun checkServerTrusted(c:Array<out X509Certificate>?,a:String?){cert=c?.firstOrNull()?:error("TV certificate missing")}
            override fun checkClientTrusted(c:Array<out X509Certificate>?,a:String?)=Unit
            override fun getAcceptedIssuers()=emptyArray<X509Certificate>()
        }
        s=socket(host,6467,identity,tm);tvCert=tm.cert;input=BufferedInputStream(s!!.inputStream);output=s!!.outputStream
        send(10,W().s(1,"atvremote").s(2,"RetroRemote"));receive(11)
        send(20,W().m(1,W().i(1,3).i(2,6)).i(3,1));receive(20)
        send(30,W().m(1,W().i(1,3).i(2,6)).i(2,1));receive(31)
    }
    private fun send(type:Int,p:W)=writeFrame(output!!,W().i(1,2).i(2,200).m(type,p).data())
    private fun receive(type:Int):Map<Int,List<Any>> { val m=R(readFrame(input!!)).fields()
        val status=m.num(2);if(status!=200)error("Pairing rejected by TV (status $status)")
        return m.sub(type)?:error("Unexpected pairing response") }
    fun submit(raw:String) {
        val code=raw.uppercase(Locale.US).filter{it in "0123456789ABCDEF"}
        require(code.length==6){"Enter the six-character code shown on TV"}
        val bytes=ByteArray(3){code.substring(it*2,it*2+2).toInt(16).toByte()}
        val md=MessageDigest.getInstance("SHA-256")
        listOf(identity.cert,tvCert?:error("TV certificate unavailable")).forEach{c->
            val k=c.publicKey as? RSAPublicKey?:error("RSA certificate required")
            fun magnitude(n:BigInteger)=n.toByteArray().dropWhile{it==0.toByte()}.toByteArray()
            md.update(magnitude(k.modulus));md.update(magnitude(k.publicExponent))}
        md.update(bytes,1,2);val secret=md.digest()
        require(secret[0]==bytes[0]){"Incorrect pairing code; check the TV screen"}
        send(40,W().b(1,secret));receive(41)
    }
    override fun close(){runCatching{s?.close()}}
}

private fun shaPin(c:X509Certificate)=MessageDigest.getInstance("SHA-256").digest(c.publicKey.encoded).joinToString(""){"%02x".format(it)}
class AtvRemote(private val host:String,private val identity:AtvIdentity,private val pin:String,private val status:(String)->Unit) {
    @Volatile private var s:SSLSocket?=null
    @Volatile var connected=false;private set
    fun run() {
        val trust=object:X509TrustManager {
            override fun checkServerTrusted(c:Array<out X509Certificate>?,a:String?){val cert=c?.firstOrNull()?:error("TV certificate missing");require(shaPin(cert)==pin){"TV identity changed; pair again"}}
            override fun checkClientTrusted(c:Array<out X509Certificate>?,a:String?)=Unit
            override fun getAcceptedIssuers()=emptyArray<X509Certificate>()
        }
        try { val sock=socket(host,6466,identity,trust);s=sock;val input=BufferedInputStream(sock.inputStream);val out=sock.outputStream;var features=0
            status("Negotiating secure remote connection…")
            while(true){val m=R(readFrame(input)).fields();when(m.keys.firstOrNull()){
                1->{val offered=m.sub(1)?.num(1)?:0;features=offered and (1 or 2 or 32 or 64 or 512)
                    val info=W().s(1,"Android").s(2,"RetroRemote").i(3,1).s(4,android.os.Build.VERSION.RELEASE).s(5,"RetroRemote").s(6,"0.1.0")
                    writeFrame(out,W().m(1,W().i(1,features).m(2,info)).data())}
                2->{writeFrame(out,W().m(2,W().i(1,features)).data());connected=true;status("Connected to Android TV")}
                8->{writeFrame(out,W().m(9,W().i(1,m.sub(8)?.num(1)?:0)).data())}
                3->error("TV rejected remote session")
            }}
        } catch(t:Throwable){status("Disconnected: "+(t.message?:t.javaClass.simpleName))}
        finally{connected=false;runCatching{s?.close()};s=null}
    }
    fun press(keyCode:Int){if(connected)try{writeFrame(s!!.outputStream,W().m(10,W().i(1,keyCode).i(2,3)).data())}catch(_:Throwable){connected=false}}
    fun close(){connected=false;runCatching{s?.close()}}
}
