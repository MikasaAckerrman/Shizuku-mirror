package moe.shizuku.manager.adb

import android.util.Log
import moe.shizuku.manager.adb.AdbProtocol.ADB_AUTH_RSAPUBLICKEY
import moe.shizuku.manager.adb.AdbProtocol.ADB_AUTH_SIGNATURE
import moe.shizuku.manager.adb.AdbProtocol.ADB_AUTH_TOKEN
import moe.shizuku.manager.adb.AdbProtocol.A_AUTH
import moe.shizuku.manager.adb.AdbProtocol.A_CLSE
import moe.shizuku.manager.adb.AdbProtocol.A_CNXN
import moe.shizuku.manager.adb.AdbProtocol.A_MAXDATA
import moe.shizuku.manager.adb.AdbProtocol.A_OKAY
import moe.shizuku.manager.adb.AdbProtocol.A_OPEN
import moe.shizuku.manager.adb.AdbProtocol.A_STLS
import moe.shizuku.manager.adb.AdbProtocol.A_STLS_VERSION
import moe.shizuku.manager.adb.AdbProtocol.A_VERSION
import moe.shizuku.manager.adb.AdbProtocol.A_WRTE
import rikka.core.util.BuildUtils
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.net.ssl.SSLSocket

private const val TAG = "AdbClient"
private const val LOCAL_CONNECT_TIMEOUT_MS = 1000
private const val REMOTE_CONNECT_TIMEOUT_MS = 3000
private const val DEFAULT_READ_TIMEOUT_MS = 20000
private const val TLS_HANDSHAKE_PROBE_MS = 250
private const val MAX_DATA_LENGTH = 1024 * 1024
private const val MAX_PRE_OPEN_SKIP = 16

class AdbClient(
    private val host: String,
    private val port: Int,
    private val key: AdbKey,
    private val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS
) : Closeable {

    companion object {
        const val READ_TIMEOUT_MS = DEFAULT_READ_TIMEOUT_MS
        const val PROBE_TIMEOUT_MS = 400
        private const val CNXN_IDENTITY = "host::features=shell_v2,cmd,stat_v2,ls_v2,fixed_push_mkdir,apex,abb,fixed_push_symlink_timestamp,abb_exec,remount_shell,track_app,sendrecv_v2_sync,sendrecv_v2_brotli,sendrecv_v2_lz4,sendrecv_v2_zstd,sendrecv_v2_dedupe,openscreen_mdns,audiotrack"

        /**
         * Lightweight protocol-level probe to verify that a local port is an
         * actual adbd listener (replies with A_STLS, A_AUTH or A_CNXN) rather
         * than a random local proxy/daemon. Does NOT require completing auth.
         */
        @JvmStatic
        fun isAdbListener(host: String, port: Int, timeoutMs: Int = PROBE_TIMEOUT_MS): Boolean {
            if (port !in 1..65535) return false
            val s = Socket()
            return try {
                s.tcpNoDelay = true
                s.soTimeout = timeoutMs
                s.connect(InetSocketAddress(host, port), timeoutMs)
                val dis = DataInputStream(s.getInputStream())
                val dos = DataOutputStream(s.getOutputStream())
                val cnxn = AdbMessage(A_CNXN, A_VERSION, A_MAXDATA, "host::")
                dos.write(cnxn.toByteArray())
                dos.flush()

                val hdr = ByteArray(AdbMessage.HEADER_LENGTH)
                dis.readFully(hdr)
                val bb = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN)
                val cmd = bb.int
                bb.int // arg0
                bb.int // arg1
                val len = bb.int
                bb.int // crc
                val magic = bb.int
                if (cmd != (magic xor -1) || len < 0 || len > MAX_DATA_LENGTH) {
                    false
                } else {
                    cmd == A_STLS || cmd == A_AUTH || cmd == A_CNXN
                }
            } catch (_: Throwable) {
                false
            } finally {
                try { s.close() } catch (_: Throwable) {}
            }
        }
    }

    private var socket: Socket? = null
    private lateinit var plainInputStream: DataInputStream
    private lateinit var plainOutputStream: DataOutputStream

    private var tlsSocket: SSLSocket? = null
    private lateinit var tlsInputStream: DataInputStream
    private lateinit var tlsOutputStream: DataOutputStream

    private var useTls = false

    private val inputStream get() = if (useTls) tlsInputStream else plainInputStream
    private val outputStream get() = if (useTls) tlsOutputStream else plainOutputStream

    @Throws(IOException::class)
    fun connect() {
        useTls = false
        val s = Socket()
        socket = s
        s.tcpNoDelay = true
        s.soTimeout = readTimeoutMs
        val timeout = if (host == "127.0.0.1" || host == "localhost") LOCAL_CONNECT_TIMEOUT_MS else REMOTE_CONNECT_TIMEOUT_MS
        s.connect(InetSocketAddress(host, port), timeout)

        plainInputStream = DataInputStream(s.getInputStream())
        plainOutputStream = DataOutputStream(s.getOutputStream())

        write(A_CNXN, A_VERSION, A_MAXDATA, CNXN_IDENTITY)

        var message = read()
        if (message.command == A_STLS) {
            if (!BuildUtils.atLeast29) {
                error("Connect to adb with TLS is not supported before Android 9")
            }
            write(A_STLS, A_STLS_VERSION, 0)

            val sslContext = key.sslContext
            val tls = sslContext.socketFactory.createSocket(s, host, port, true) as SSLSocket
            tlsSocket = tls
            tls.soTimeout = readTimeoutMs
            tls.startHandshake()
            Log.d(TAG, "Handshake succeeded.")

            tlsInputStream = DataInputStream(tls.inputStream)
            tlsOutputStream = DataOutputStream(tls.outputStream)
            useTls = true

            // После mTLS-handshake в AOSP adbd сам сразу отправляет A_CNXN
            // (если клиентский сертификат уже доверенный; именно поэтому нельзя
            // слать второй A_CNXN безусловно — иначе в буфере остаётся лишний
            // ответ adbd, который затем вычитывается вместо A_OKAY в shellCommand()).
            // Если же проприетарный adbd ждёт A_CNXN от клиента, короткий probe
            // вернёт SocketTimeoutException и мы отправим A_CNXN ровно один раз.
            val serverInitiated = try {
                tls.soTimeout = TLS_HANDSHAKE_PROBE_MS
                read()
            } catch (_: java.net.SocketTimeoutException) {
                null
            } finally {
                tls.soTimeout = readTimeoutMs
            }

            if (serverInitiated != null) {
                message = serverInitiated
            } else {
                write(A_CNXN, A_VERSION, A_MAXDATA, CNXN_IDENTITY)
                message = read()
            }
            if (message.command == A_AUTH) {
                if (message.arg0 != ADB_AUTH_TOKEN) error("not A_AUTH ADB_AUTH_TOKEN")
                write(A_AUTH, ADB_AUTH_SIGNATURE, 0, key.sign(message.data))
                message = read()
                if (message.command != A_CNXN) {
                    write(A_AUTH, ADB_AUTH_RSAPUBLICKEY, 0, key.adbPublicKey)
                    message = read()
                }
            }
        } else if (message.command == A_AUTH) {
            if (message.arg0 != ADB_AUTH_TOKEN) error("not A_AUTH ADB_AUTH_TOKEN")
            write(A_AUTH, ADB_AUTH_SIGNATURE, 0, key.sign(message.data))

            message = read()
            if (message.command != A_CNXN) {
                write(A_AUTH, ADB_AUTH_RSAPUBLICKEY, 0, key.adbPublicKey)
                message = read()
            }
        }

        if (message.command != A_CNXN) error("not A_CNXN: ${message.toStringShort()}")
    }

    /**
     * Switches adbd to TCP/IP mode on [port] (equivalent to `adb tcpip <port>`).
     * Works over an already-authenticated ADB/WADB connection without root.
     */
    @Throws(IOException::class)
    fun tcpip(port: Int = 5555): Boolean {
        if (port !in 1..65535) return false
        val localId = 1
        write(A_OPEN, localId, 0, "tcpip:$port")

        var message = read()
        var skipped = 0
        while (message.command != A_OKAY && message.command != A_CLSE) {
            if (++skipped > MAX_PRE_OPEN_SKIP) {
                return false
            }
            Log.w(TAG, "Unexpected message before tcpip open response: ${message.toStringShort()}")
            message = read()
        }

        return when (message.command) {
            A_OKAY -> {
                val serverRemoteId = message.arg0
                val response = StringBuilder()
                val prevTimeout = tlsSocket?.soTimeout ?: socket?.soTimeout ?: readTimeoutMs
                try {
                    // После A_OKAY adbd отправляет "restarting in TCP mode port: 5555\n" и
                    // перезапускает сокет. На части прошивок вместо A_CLSE TLS-поток просто
                    // замолкает до таймаута, поэтому ждём ответ коротко (2 с), а не 20 с.
                    if (useTls) {
                        tlsSocket?.soTimeout = 2000
                    } else {
                        socket?.soTimeout = 2000
                    }
                    while (true) {
                        val pkt = read()
                        val remoteId = if (pkt.arg0 != 0) pkt.arg0 else serverRemoteId
                        if (pkt.command == A_WRTE) {
                            if (pkt.data_length > 0 && pkt.data != null) {
                                response.append(String(pkt.data))
                            }
                            write(A_OKAY, localId, remoteId)
                        } else if (pkt.command == A_CLSE) {
                            write(A_CLSE, localId, remoteId)
                            break
                        }
                    }
                } catch (e: IOException) {
                    // adbd often resets or stalls the transport immediately after accepting tcpip:<port>
                    Log.d(TAG, "tcpip($port) stream ended after A_OKAY: ${e.message}")
                } finally {
                    runCatching {
                        if (useTls) {
                            tlsSocket?.soTimeout = prevTimeout
                        } else {
                            socket?.soTimeout = prevTimeout
                        }
                    }
                }
                val text = response.toString()
                Log.i(TAG, "tcpip($port) response: $text")
                !text.contains("unable", ignoreCase = true) && !text.contains("error", ignoreCase = true)
            }
            A_CLSE -> {
                val remoteId = message.arg0
                runCatching { write(A_CLSE, localId, remoteId) }
                false
            }
            else -> false
        }
    }

    fun shellCommand(command: String, listener: ((ByteArray) -> Unit)?) {
        val localId = 1
        write(A_OPEN, localId, 0, "shell:$command")

        var message = read()
        var skipped = 0
        while (message.command != A_OKAY && message.command != A_CLSE) {
            if (++skipped > MAX_PRE_OPEN_SKIP) {
                error("not A_OKAY or A_CLSE after $skipped messages: ${message.toStringShort()}")
            }
            val msgSummary = "Unexpected message before open response: ${message.toStringShort()}"
            Log.w(TAG, "$msgSummary, reading next")
            if (message.command == A_CNXN) {
                listener?.invoke("[$msgSummary]\n".toByteArray())
            }
            message = read()
        }

        when (message.command) {
            A_OKAY -> {
                val serverRemoteId = message.arg0
                while (true) {
                    message = read()
                    val remoteId = if (message.arg0 != 0) message.arg0 else serverRemoteId
                    if (message.command == A_WRTE) {
                        if (message.data_length > 0 && message.data != null) {
                            listener?.invoke(message.data)
                        }
                        write(A_OKAY, localId, remoteId)
                    } else if (message.command == A_CLSE) {
                        write(A_CLSE, localId, remoteId)
                        break
                    } else {
                        Log.w(TAG, "Ignored unexpected packet in stream: ${message.toStringShort()}")
                    }
                }
            }
            A_CLSE -> {
                val remoteId = message.arg0
                write(A_CLSE, localId, remoteId)
                throw IOException("adbd rejected shell stream (A_CLSE remoteId=$remoteId)")
            }
        }
    }

    private fun write(command: Int, arg0: Int, arg1: Int, data: ByteArray? = null) = write(AdbMessage(command, arg0, arg1, data))

    private fun write(command: Int, arg0: Int, arg1: Int, data: String) = write(AdbMessage(command, arg0, arg1, data))

    private fun write(message: AdbMessage) {
        outputStream.write(message.toByteArray())
        outputStream.flush()
        Log.d(TAG, "write ${message.toStringShort()}")
    }

    private fun read(): AdbMessage {
        val buffer = ByteBuffer.allocate(AdbMessage.HEADER_LENGTH).order(ByteOrder.LITTLE_ENDIAN)

        inputStream.readFully(buffer.array(), 0, 24)

        val command = buffer.int
        val arg0 = buffer.int
        val arg1 = buffer.int
        val dataLength = buffer.int
        val checksum = buffer.int
        val magic = buffer.int

        if (dataLength < 0 || dataLength > MAX_DATA_LENGTH) {
            error("invalid data_length $dataLength")
        }
        val data: ByteArray?
        if (dataLength > 0) {
            data = ByteArray(dataLength)
            inputStream.readFully(data, 0, dataLength)
        } else {
            data = null
        }
        val message = AdbMessage(command, arg0, arg1, dataLength, checksum, magic, data)
        message.validateOrThrow()
        Log.d(TAG, "read ${message.toStringShort()}")
        return message
    }

    override fun close() {
        try { tlsSocket?.close() } catch (_: Throwable) {}
        try { socket?.close() } catch (_: Throwable) {}
    }
}
