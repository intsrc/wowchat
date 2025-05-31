package wowchat.common

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

import io.netty.channel.EventLoopGroup
import wowchat.redis.Redis
import wowchat.game.GameCommandHandler

import scala.collection.mutable

object Global {

  var group: EventLoopGroup = _
  var config: WowChatConfig = _

  var redis: Redis = _
  var game: Option[GameCommandHandler] = None

  val wowToRedis = new mutable.HashMap[(Byte, Option[String]), mutable.Set[RedisChannelConfig]]
    with mutable.MultiMap[(Byte, Option[String]), RedisChannelConfig]
  val guildEventsToRedis = new mutable.HashMap[String, mutable.Set[String]]
    with mutable.MultiMap[String, String]

  def getTime: String = {
    LocalDateTime.now.format(DateTimeFormatter.ofPattern("HH:mm:ss"))
  }
}
