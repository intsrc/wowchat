# WoWChat - Redis Integration

This is a modified version of WoWChat that sends World of Warcraft chat messages to Redis instead of Discord. This allows you to process WoW chat messages in your own applications via Redis pub/sub.

## What's Changed

- **Discord Integration Removed**: No more Discord bot functionality
- **Redis Integration Added**: All chat messages are published to Redis channels
- **JSON Message Format**: Messages are published as structured JSON for easy processing
- **Configurable Channels**: Map different WoW chat types to different Redis channels

## Configuration

Create a `wowchat.conf` file based on the provided template. Key sections:

### Redis Configuration
```hocon
redis {
  host=localhost
  port=6379
  timeout=2000
  # password=your_redis_password  # Optional
  
  # Default channels for different message types
  status_channel="wow:status"
  guild_channel="wow:guild" 
  achievement_channel="wow:achievements"
}
```

### Channel Mapping
```hocon
chat {
  channels=[
    {
      direction=wow_to_redis
      wow {
        type=Guild
        format="[%user]: %message"
      }
      redis {
        channel="wow:guild-chat"
        format="[%user]: %message"
      }
    }
    # Add more channel mappings as needed
  ]
}
```

## Message Format

All messages published to Redis are JSON objects with this structure:

```json
{
  "messageType": "guild",
  "channel": "optional_wow_channel_name",
  "user": "PlayerName",
  "message": "[PlayerName]: Hello everyone!",
  "timestamp": "14:30:25",
  "realm": "Your Realm Name",
  "character": "BotCharacterName"
}
```

### Message Types
- `guild` - Guild chat messages
- `officer` - Officer chat messages  
- `say` - Say chat messages
- `yell` - Yell chat messages
- `emote` - Emote messages
- `system` - System messages
- `whisper` - Whisper messages
- `channel` - Custom channel messages
- `guild_notification` - Guild events (join/leave/promote/etc)
- `achievement` - Achievement notifications
- `status` - Bot status updates

## Usage

1. **Start Redis**: Make sure Redis is running on your configured host/port
2. **Configure WoW Account**: Set up a dedicated WoW account for the bot
3. **Run WoWChat**: `java -jar wowchat.jar wowchat.conf`
4. **Subscribe to Messages**: Use Redis clients to subscribe to the channels

## Sample Redis Subscriber (Python)

```python
import redis
import json

def handle_wow_message(message_data):
    """Process a WoW chat message"""
    try:
        msg = json.loads(message_data)
        print(f"[{msg['timestamp']}] {msg['messageType']}: {msg['message']}")
        
        # Process different message types
        if msg['messageType'] == 'guild':
            # Handle guild chat
            process_guild_message(msg)
        elif msg['messageType'] == 'achievement':
            # Handle achievements
            process_achievement(msg)
        elif msg['messageType'] == 'status':
            # Handle bot status updates
            process_status_update(msg)
            
    except json.JSONDecodeError:
        print(f"Invalid JSON: {message_data}")

def process_guild_message(msg):
    """Process guild chat messages"""
    user = msg.get('user', 'Unknown')
    message = msg.get('message', '')
    print(f"Guild: {user} said: {message}")

def process_achievement(msg):
    """Process achievement notifications"""
    print(f"Achievement: {msg['message']}")

def process_status_update(msg):
    """Process bot status updates"""
    print(f"Status: {msg['message']}")

# Redis subscriber
r = redis.Redis(host='localhost', port=6379, decode_responses=True)
pubsub = r.pubsub()

# Subscribe to all wow channels
pubsub.psubscribe('wow:*')

print("Listening for WoW messages...")
for message in pubsub.listen():
    if message['type'] == 'pmessage':
        channel = message['channel']
        data = message['data']
        print(f"Received on {channel}: {data}")
        handle_wow_message(data)
```

## Sample Redis Subscriber (Node.js)

```javascript
const redis = require('redis');

const client = redis.createClient({
    host: 'localhost',
    port: 6379
});

client.on('error', (err) => {
    console.error('Redis error:', err);
});

// Subscribe to all wow channels
client.psubscribe('wow:*');

client.on('pmessage', (pattern, channel, message) => {
    try {
        const msg = JSON.parse(message);
        console.log(`[${msg.timestamp}] ${msg.messageType}: ${msg.message}`);
        
        // Process different message types
        switch(msg.messageType) {
            case 'guild':
                processGuildMessage(msg);
                break;
            case 'achievement':
                processAchievement(msg);
                break;
            case 'status':
                processStatusUpdate(msg);
                break;
        }
    } catch (error) {
        console.error('Invalid JSON:', message);
    }
});

function processGuildMessage(msg) {
    const user = msg.user || 'Unknown';
    console.log(`Guild: ${user} said: ${msg.message}`);
}

function processAchievement(msg) {
    console.log(`Achievement: ${msg.message}`);
}

function processStatusUpdate(msg) {
    console.log(`Status: ${msg.message}`);
}

console.log('Listening for WoW messages...');
```

## Building

Build with Maven:
```bash
mvn clean package
```

This will create `wowchat.jar` in the `target` directory.

## Supported WoW Versions

- Vanilla (1.12.x)
- The Burning Crusade (2.4.3)
- Wrath of the Lich King (3.3.5)
- Cataclysm (4.3.4)
- Mists of Pandaria (5.4.8)

## Channel Directions

- `wow_to_redis`: Only send messages from WoW to Redis
- `redis_to_wow`: Only send messages from Redis to WoW (not implemented in this version)
- `both`: Both directions (not applicable for this Redis-only version)

## Tips

1. **Dedicated Account**: Use a separate WoW account for the bot
2. **Channel Mapping**: Configure only the channels you need to avoid spam
3. **Redis Security**: Use Redis AUTH if running in production
4. **Message Processing**: Process messages asynchronously to avoid blocking
5. **Error Handling**: Implement proper error handling for Redis connection issues

## Troubleshooting

- **Connection Failed**: Check Redis host/port configuration
- **No Messages**: Verify channel mappings in configuration
- **Authentication Error**: Check WoW account credentials
- **Character Not Found**: Ensure character exists on the specified realm 