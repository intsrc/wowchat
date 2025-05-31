package wowchat.redis

import wowchat.common._
import com.typesafe.scalalogging.StrictLogging
import redis.clients.jedis.{Jedis, JedisPool, JedisPoolConfig}
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import wowchat.game.GamePackets

import scala.collection.mutable
import scala.util.{Success, Failure, Try}

case class WowMessage(
  messageType: String,
  channel: Option[String],
  user: Option[String], 
  message: String,
  timestamp: String,
  realm: String,
  character: String
)

class Redis(redisConnectionCallback: CommonConnectionCallback) extends GamePackets with StrictLogging {

  private val jedisPool: JedisPool = {
    val poolConfig = new JedisPoolConfig()
    poolConfig.setMaxTotal(10)
    poolConfig.setMaxIdle(5)
    poolConfig.setMinIdle(1)
    poolConfig.setTestOnBorrow(true)
    poolConfig.setTestOnReturn(true)
    poolConfig.setTestWhileIdle(true)
    
    new JedisPool(poolConfig, Global.config.redis.host, Global.config.redis.port, 
                  Global.config.redis.timeout, Global.config.redis.password.orNull)
  }

  private val objectMapper = new ObjectMapper()
  objectMapper.registerModule(DefaultScalaModule)

  private var connected = false
  private var firstConnect = true

  // Initialize Redis connection
  init()

  private def init(): Unit = {
    try {
      withJedis { jedis =>
        jedis.ping()
        logger.info("Successfully connected to Redis")
        connected = true
        
        if (firstConnect) {
          redisConnectionCallback.connected
          firstConnect = false
        } else {
          redisConnectionCallback.reconnected
        }
      }
    } catch {
      case e: Exception =>
        logger.error("Failed to connect to Redis", e)
        connected = false
        redisConnectionCallback.error
    }
  }

  private def withJedis[T](operation: Jedis => T): T = {
    val jedis = jedisPool.getResource
    try {
      operation(jedis)
    } finally {
      jedis.close()
    }
  }

  def changeRealmStatus(message: String): Unit = {
    publishStatusMessage("realm", message)
  }

  def changeGuildStatus(message: String): Unit = {
    publishStatusMessage("guild", message)
  }

  private def publishStatusMessage(statusType: String, message: String): Unit = {
    if (!connected) return
    
    try {
      val statusMessage = WowMessage(
        messageType = "status",
        channel = Some(statusType),
        user = None,
        message = message,
        timestamp = Global.getTime,
        realm = Global.config.wow.realmlist.name,
        character = Global.config.wow.character
      )
      
      val jsonMessage = objectMapper.writeValueAsString(statusMessage)
      withJedis { jedis =>
        jedis.publish(Global.config.redis.statusChannel, jsonMessage)
      }
      logger.debug(s"Published status message to Redis: $jsonMessage")
    } catch {
      case e: Exception =>
        logger.error("Failed to publish status message to Redis", e)
    }
  }

  def sendMessageFromWow(from: Option[String], message: String, wowType: Byte, wowChannel: Option[String]): Unit = {
    if (!connected) return

    Global.wowToRedis.get((wowType, wowChannel.map(_.toLowerCase))).foreach(redisChannels => {
      // Strip color coding and resolve links (simplified version)
      val cleanMessage = stripColorCoding(message)

      redisChannels.foreach { channelConfig =>
        try {
          val formatted = channelConfig
            .format
            .replace("%time", Global.getTime)
            .replace("%user", from.getOrElse(""))
            .replace("%message", cleanMessage)
            .replace("%target", wowChannel.getOrElse(""))

          val filter = shouldFilter(channelConfig.filters, formatted)
          
          if (!filter) {
            val wowMessage = WowMessage(
              messageType = getMessageTypeName(wowType),
              channel = wowChannel,
              user = from,
              message = formatted,
              timestamp = Global.getTime,
              realm = Global.config.wow.realmlist.name,
              character = Global.config.wow.character
            )
            
            val jsonMessage = objectMapper.writeValueAsString(wowMessage)
            withJedis { jedis =>
              jedis.publish(channelConfig.channel, jsonMessage)
            }
            logger.info(s"WoW->Redis(${channelConfig.channel}) $formatted")
          } else {
            logger.info(s"FILTERED WoW->Redis(${channelConfig.channel}) $formatted")
          }
        } catch {
          case e: Exception =>
            logger.error(s"Failed to publish message to Redis channel ${channelConfig.channel}", e)
        }
      }
    })
  }

  def sendGuildNotification(eventKey: String, message: String): Unit = {
    if (!connected) return

    try {
      Global.guildEventsToRedis
        .getOrElse(eventKey, Set(Global.config.redis.guildChannel))
        .foreach(channel => {
          val wowMessage = WowMessage(
            messageType = "guild_notification",
            channel = Some(eventKey),
            user = None,
            message = message,
            timestamp = Global.getTime,
            realm = Global.config.wow.realmlist.name,
            character = Global.config.wow.character
          )
          
          val jsonMessage = objectMapper.writeValueAsString(wowMessage)
          withJedis { jedis =>
            jedis.publish(channel, jsonMessage)
          }
          logger.info(s"WoW->Redis(${channel}) $message")
        })
    } catch {
      case e: Exception =>
        logger.error("Failed to publish guild notification to Redis", e)
    }
  }

  def sendAchievementNotification(name: String, achievementId: Int): Unit = {
    if (!connected) return

    val notificationConfig = Global.config.guildConfig.notificationConfigs("achievement")
    if (!notificationConfig.enabled) {
      return
    }

    try {
      val formatted = notificationConfig
        .format
        .replace("%time", Global.getTime)
        .replace("%user", name)
        .replace("%achievement", s"Achievement #$achievementId") // Simplified achievement resolution

      val wowMessage = WowMessage(
        messageType = "achievement",
        channel = None,
        user = Some(name),
        message = formatted,
        timestamp = Global.getTime,
        realm = Global.config.wow.realmlist.name,
        character = Global.config.wow.character
      )

      val jsonMessage = objectMapper.writeValueAsString(wowMessage)
      withJedis { jedis =>
        jedis.publish(Global.config.redis.achievementChannel, jsonMessage)
      }
      logger.info(s"Achievement->Redis: $formatted")
    } catch {
      case e: Exception =>
        logger.error("Failed to publish achievement notification to Redis", e)
    }
  }

  private def getMessageTypeName(wowType: Byte): String = {
    wowType match {
      case ChatEvents.CHAT_MSG_SAY => "say"
      case ChatEvents.CHAT_MSG_GUILD => "guild"
      case ChatEvents.CHAT_MSG_OFFICER => "officer"
      case ChatEvents.CHAT_MSG_YELL => "yell"
      case ChatEvents.CHAT_MSG_EMOTE => "emote"
      case ChatEvents.CHAT_MSG_SYSTEM => "system"
      case ChatEvents.CHAT_MSG_WHISPER => "whisper"
      case ChatEvents.CHAT_MSG_CHANNEL => "channel"
      case _ => "unknown"
    }
  }

  private def stripColorCoding(message: String): String = {
    // Remove WoW color codes (|cxxxxxxxx and |r)
    message
      .replaceAll("\\|c[0-9a-fA-F]{8}", "")
      .replaceAll("\\|r", "")
  }

  private def shouldFilter(filtersConfig: Option[FiltersConfig], message: String): Boolean = {
    filtersConfig
      .fold(Global.config.filters)(Some(_))
      .exists(filters => filters.enabled && filters.patterns.exists(message.matches))
  }

  def shutdown(): Unit = {
    try {
      connected = false
      jedisPool.close()
      logger.info("Redis connection closed")
    } catch {
      case e: Exception =>
        logger.error("Error closing Redis connection", e)
    }
  }
} 