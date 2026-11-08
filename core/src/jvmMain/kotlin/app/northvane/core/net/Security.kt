package app.northvane.core.net

import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.net.UnknownHostException
import javax.net.SocketFactory

/**
 * Last line of defence against accidental cleartext traffic: any http:// request
 * must be explicitly tagged [LanCleartextAllowed] and target a private-network
 * host. This runs in-app in addition to the Android network security config.
 */
class CleartextGuardInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!request.url.isHttps) {
            val allowed = request.tag(LanCleartextAllowed::class.java) != null
            if (!allowed) throw IOException("Blocked insecure (http) request")
            if (UrlPolicy.isIpLiteral(request.url.host) && !UrlPolicy.isPrivateIpLiteral(request.url.host)) {
                throw IOException("Blocked insecure request to a public address")
            }
            if (request.header("Authorization") != null) {
                throw IOException("Blocked credentials over insecure connection")
            }
        }
        return chain.proceed(request)
    }
}

/**
 * DNS used for cleartext local-network requests: only returns private
 * addresses, so a hostname that (re)resolves to the public internet — for
 * example via DNS rebinding or a typo — can't receive unencrypted traffic.
 */
class PrivateNetworkDns(private val delegate: Dns = Dns.SYSTEM) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val privateOnly = delegate.lookup(hostname).filter { UrlPolicy.isPrivateAddress(it) }
        if (privateOnly.isEmpty()) {
            throw UnknownHostException(
                "$hostname is not on a private network; plain HTTP is only allowed for local servers",
            )
        }
        return privateOnly
    }
}

/**
 * Sockets for cleartext local-network requests that refuse to connect anywhere
 * but a private-network address. [PrivateNetworkDns] isn't consulted for IP
 * addresses written in the URL, so without this a local server could redirect
 * a request to http://<public address>/.
 */
class PrivateNetworkSocketFactory : SocketFactory() {
    override fun createSocket(): Socket = GuardedSocket()
    override fun createSocket(host: String, port: Int): Socket = createSocket().apply { connect(InetSocketAddress(host, port)) }
    override fun createSocket(host: InetAddress, port: Int): Socket = createSocket().apply { connect(InetSocketAddress(host, port)) }
    override fun createSocket(host: String, port: Int, localHost: InetAddress?, localPort: Int): Socket =
        createSocket().apply { bind(InetSocketAddress(localHost, localPort)); connect(InetSocketAddress(host, port)) }
    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
        createSocket().apply { bind(InetSocketAddress(localAddress, localPort)); connect(InetSocketAddress(address, port)) }

    private class GuardedSocket : Socket() {
        override fun connect(endpoint: SocketAddress, timeout: Int) {
            val address = (endpoint as? InetSocketAddress)?.address
            if (address == null || !UrlPolicy.isPrivateAddress(address)) {
                throw ConnectException("Plain HTTP is only allowed for servers on your local network")
            }
            super.connect(endpoint, timeout)
        }
    }
}

class DefaultHeadersInterceptor(private val userAgent: String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val builder: Request.Builder = request.newBuilder()
        if (request.header("User-Agent") == null) builder.header("User-Agent", userAgent)
        return chain.proceed(builder.build())
    }
}
