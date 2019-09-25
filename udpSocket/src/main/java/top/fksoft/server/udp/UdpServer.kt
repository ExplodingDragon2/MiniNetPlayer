package top.fksoft.server.udp

import jdkUtils.data.AtomicUtils
import jdkUtils.logcat.Logger
import top.fksoft.bean.NetworkInfo
import top.fksoft.server.udp.bean.Packet
import top.fksoft.server.udp.callback.Binder
import top.fksoft.server.udp.callback.PacketListener
import top.fksoft.server.udp.factory.HashFactory
import java.io.Closeable
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * # 建立一个 UDP 管理服务器
 * > 封装 的DatagramSocket
 * 此udp传输无加密
 * 在指定 mtu大小时，要保证小于网络传输层最小 MTU 大小（保证数据包不分片）
 *
 *  datagramSocket DatagramSocket 已经初始化的 ``` DatagramSocket ```
 *
 *  mtuSize Int 网络MTU大小
 *
 *  threadPool ExecutorService 非线性线程池
 *
 *  hashFactory HashFactory 哈希生成工厂类
 *
 * 默认已经全部实现，绑定了 ```31412``` 端口，使用标准互联网MTU大小，依赖 CRC32 来校验
 *
 */
class UdpServer(
    private val datagramSocket: DatagramSocket = DatagramSocket(31412),
    private val mtuSize: Int = InternetMTU,
    private val threadPool: ExecutorService = Executors.newCachedThreadPool(),
    private val hashFactory: HashFactory = HashFactory.default
) : Closeable {
    private val logger = Logger.getLogger(this)
    /**
     * # 实际数据包大小
     *  INTERNET UDP 减去报文和包头大小
     */
    val packetSize: Int = mtuSize - 20 - 8

    /**
     * # 数据包校验类型
     * 如果 UDP 包头不符合则丢弃
     */
    private val tag = ByteArray(3)

    fun setTAG(zero: Byte = 32, first: Byte = 16, second: Byte = 23) {
        tag[0] = zero
        tag[1] = first
        tag[2] = second

    }


    /**
     * 服务器是否关闭
     */
    val isClosed: Boolean
        get() = datagramSocket.isBound.not() || datagramSocket.isClosed

    private val receiveMap = ConcurrentHashMap<String, Binder>()
    // 监听的
    private val sendPacketData = ByteArray(packetSize)
    //发送数据包时使用中转


    init {
        if (isClosed) {
            throw SocketException("连接存在问题，无法初始化.")
        }
        setTAG() //默认 TAG
    }


    /**
     * 得到本地端口号
     */
    val localPort by lazy {
        datagramSocket.localPort
    }


    /**
     * # 发送数据包
     *
     * > 将封装好的数据包发送到目标服务器
     *
     * @param packet Packet 数据包
     * @param info NetworkInfo 远程服务器ip + 端口
     * @return Boolean 是否由系统发送
     *
     *
     */
    @Synchronized
    fun sendPacket(packet: Packet, info: NetworkInfo): Boolean {
        try {
            synchronized(sendPacketData) {
                val dataSize = packet.encode(sendPacketData, tag.size + hashFactory.hashByteSize + Short.SIZE_BYTES)
                //写入数据
                if (dataSize == -1) {
                    throw RuntimeException("在写入时发生错误 dataSize = -1 .")
                }
                hashFactory.createToByteArray(packet.hashSrc, sendPacketData, tag.size)
                //定义数据类型（伪）
                System.arraycopy(
                    AtomicUtils.shortToBytes(dataSize.toShort()),
                    0,
                    sendPacketData,
                    tag.size + hashFactory.hashByteSize,
                    Short.SIZE_BYTES
                )
                //定义数据实际长度
                if (isClosed) {
                    throw IOException("服务器已经关闭.")
                }
                datagramSocket.send(
                    DatagramPacket(
                        sendPacketData,
                        sendPacketData.size,
                        InetAddress.getByName(info.ip),
                        info.port
                    )
                )
                //进行数据包发送
            }
        } catch (e: Exception) {
            logger.error("此实例在发送数据到$info 时出现问题.", e)
            return false
        }
        logger.info("发送数据包到$info 完成.")
        return true
    }

    /**
     *
     * @param hashSrc String
     * @param listener PacketListener
     */
    @Synchronized
    fun bindReceive(hashSrc: String, listener: PacketListener) {

    }


    override fun close() {

    }


    companion object {
        /**
         * 标准 Internet 下 MTU 大小
         */
        const val InternetMTU = 576
        /**
         * 一般情况下 局域网下最大MTU
         */
        const val LocalMTU = 1480

    }
}