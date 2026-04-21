package nl.vanvrouwerff.iptv.data.db

import nl.vanvrouwerff.iptv.data.Channel
import nl.vanvrouwerff.iptv.data.ContentType

fun ChannelEntity.toDomain(): Channel = Channel(
    id = id,
    name = name,
    logoUrl = logoUrl,
    groupTitle = groupTitle,
    streamUrl = streamUrl,
    epgChannelId = epgChannelId,
    type = runCatching { ContentType.valueOf(type) }.getOrDefault(ContentType.TV),
)

fun Channel.toEntity(sortIndex: Int): ChannelEntity = ChannelEntity(
    id = id,
    name = name,
    logoUrl = logoUrl,
    groupTitle = groupTitle,
    streamUrl = streamUrl,
    epgChannelId = epgChannelId,
    sortIndex = sortIndex,
    type = type.name,
)
