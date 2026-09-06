package com.rockmobile.devicecontrol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Typed wire boundary for the controller directory; raw JSON never leaves this package. */
internal object DirectoryJson {
    val codec = Json { ignoreUnknownKeys = true; isLenient = false; explicitNulls = false }
}

@Serializable
internal data class DirectoryDto(
    @SerialName("protocol_version") val protocolVersion: Int,
    @SerialName("generated_at") val generatedAt: String,
    @SerialName("directory_revision") val revision: Long,
    @SerialName("granted_scopes") val grantedScopes: List<String>,
    val devices: List<DirectoryEntryDto>,
)

@Serializable
internal data class DirectoryEntryDto(
    @SerialName("device_id") val id: String,
    @SerialName("device_display_name") val displayName: String,
    @SerialName("device_type") val type: String,
    val roles: List<String>,
    val capabilities: CapabilitiesDto,
    val presence: PresenceDto,
    @SerialName("state_freshness") val freshness: FreshnessDto,
)

@Serializable internal data class CapabilitiesDto(val revision: Long, val items: List<CapabilityDto>)
@Serializable internal data class CapabilityDto(val name: String, val version: Int)
@Serializable internal data class PresenceDto(
    val status: String,
    @SerialName("last_seen_at") val lastSeenAt: String? = null,
    @SerialName("offline_reason") val offlineReason: String? = null,
)
@Serializable internal data class FreshnessDto(
    val status: String,
    @SerialName("observed_at") val observedAt: String? = null,
    @SerialName("received_at") val receivedAt: String? = null,
    @SerialName("stale_after") val staleAfter: String? = null,
)

@Serializable
internal data class WireHeaderDto(
    @SerialName("protocol_version") val protocolVersion: Int,
    @SerialName("message_id") val messageId: String,
    val type: String,
    @SerialName("sent_at") val sentAt: String,
)
@Serializable
internal data class UnknownMessageDto(
    @SerialName("protocol_version") val protocolVersion: Int,
    @SerialName("message_id") val messageId: String,
    val type: String,
    @SerialName("sent_at") val sentAt: String,
    val payload: IgnoredPayloadDto,
)
@Serializable internal data class IgnoredPayloadDto(val marker: String? = null)

@Serializable internal data class SnapshotMessageDto(
    @SerialName("protocol_version") val protocolVersion: Int,
    @SerialName("message_id") val messageId: String,
    val type: String,
    @SerialName("sent_at") val sentAt: String,
    val payload: SnapshotPayloadDto,
)
@Serializable internal data class SnapshotPayloadDto(@SerialName("event_id") val eventId: String, val directory: DirectoryDto)

@Serializable internal data class UpsertMessageDto(
    @SerialName("protocol_version") val protocolVersion: Int,
    @SerialName("message_id") val messageId: String,
    val type: String,
    @SerialName("sent_at") val sentAt: String,
    val payload: UpsertPayloadDto,
)
@Serializable internal data class UpsertPayloadDto(
    @SerialName("event_id") val eventId: String,
    @SerialName("directory_revision") val revision: Long,
    val device: DirectoryEntryDto,
)

@Serializable internal data class RemovalMessageDto(
    @SerialName("protocol_version") val protocolVersion: Int,
    @SerialName("message_id") val messageId: String,
    val type: String,
    @SerialName("sent_at") val sentAt: String,
    val payload: RemovalPayloadDto,
)
@Serializable internal data class RemovalPayloadDto(
    @SerialName("event_id") val eventId: String,
    @SerialName("directory_revision") val revision: Long,
    @SerialName("device_id") val deviceId: String,
    val reason: String,
)

@Serializable internal data class ProtocolErrorMessageDto(
    @SerialName("protocol_version") val protocolVersion: Int,
    @SerialName("message_id") val messageId: String,
    val type: String,
    @SerialName("sent_at") val sentAt: String,
    val payload: ProtocolErrorPayloadDto,
)
@Serializable internal data class ProtocolErrorPayloadDto(val error: ProtocolErrorDto)
@Serializable internal data class ProtocolErrorDto(val code: String, val message: String)

internal sealed interface DirectoryWireMessage {
    data class Snapshot(val directory: DirectoryDto) : DirectoryWireMessage
    data class Upsert(val revision: Long, val device: DirectoryEntryDto) : DirectoryWireMessage
    data class Removed(val revision: Long, val deviceId: String, val reason: RemovalReason) : DirectoryWireMessage
    data object ResyncRequired : DirectoryWireMessage
    data object IgnoredUnknown : DirectoryWireMessage
}

internal enum class RemovalReason { Revoked, NoLongerPermitted, Removed }

/** Unknown namespaced event types are ignored; a malformed known event is rejected here. */
internal fun decodeDirectoryMessage(text: String): DirectoryWireMessage {
    val header = DirectoryJson.codec.decodeFromString<WireHeaderDto>(text)
    require(header.protocolVersion == 1) { "Unsupported control protocol" }
    return when (header.type) {
        "directory.snapshot" -> DirectoryJson.codec.decodeFromString<SnapshotMessageDto>(text).payload.directory.let(DirectoryWireMessage::Snapshot)
        "directory.upsert" -> DirectoryJson.codec.decodeFromString<UpsertMessageDto>(text).payload.let { DirectoryWireMessage.Upsert(it.revision, it.device) }
        "directory.removed" -> DirectoryJson.codec.decodeFromString<RemovalMessageDto>(text).payload.let {
            val reason = when (it.reason) {
                "revoked" -> RemovalReason.Revoked
                "no_longer_permitted" -> RemovalReason.NoLongerPermitted
                "removed" -> RemovalReason.Removed
                else -> throw IllegalArgumentException("Unknown removal reason")
            }
            DirectoryWireMessage.Removed(it.revision, it.deviceId, reason)
        }
        "protocol.error" -> DirectoryJson.codec.decodeFromString<ProtocolErrorMessageDto>(text).payload.error.let {
            if (it.code == "directory_resync_required") DirectoryWireMessage.ResyncRequired else DirectoryWireMessage.IgnoredUnknown
        }
        else -> DirectoryJson.codec.decodeFromString<UnknownMessageDto>(text).let { DirectoryWireMessage.IgnoredUnknown }
    }
}
