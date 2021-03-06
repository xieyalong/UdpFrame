package cn.leo.udp.net

/**
 * Created by Leo on 2018/4/27.
 */
internal interface UdpInterface {
    fun getSender(host: String, port: Int = Config.DEF_PORT, packetProcessor: PacketProcessor = DefaultPacketProcessor()): UdpSender
    fun subscribe(onDataArrivedListener: OnDataArrivedListener, packetProcessor: PacketProcessor = DefaultPacketProcessor())
    fun subscribe(port: Int, onDataArrivedListener: OnDataArrivedListener, packetProcessor: PacketProcessor = DefaultPacketProcessor())
    fun unSubscribe(onDataArrivedListener: OnDataArrivedListener)
    fun unSubscribe(port: Int)
    fun close()
}