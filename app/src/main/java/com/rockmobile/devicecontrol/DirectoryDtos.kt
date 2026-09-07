package com.rockmobile.devicecontrol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Typed wire boundary for the controller directory; raw JSON never leaves this package. */
internal object DirectoryJson {
    val codec = Json { ignoreUnknownKeys = true; isLenient = false; explicitNulls = false; encodeDefaults = true }
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
/**
 * The serializer owns the wire shape. Known variants are validated into [ControlCapability]
 * immediately; optional fields exist solely for forward-compatible unknown capability decoding.
 */
@Serializable internal data class CapabilityDto(
    val name: String,
    val version: Int,
    val actions: List<String>? = null,
    val sources: List<String>? = null,
    val minimum: Int? = null,
    val maximum: Int? = null,
    val step: Int? = null,
    val mute: Boolean? = null,
    @SerialName("discovery_ttl_seconds") val discoveryTtlSeconds: Int? = null,
    val modes: List<String>? = null,
)
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
@Serializable internal data class ProtocolErrorPayloadDto(val error: ProtocolErrorDto, @SerialName("command_id") val commandId: String? = null)
@Serializable internal data class ProtocolErrorDto(val code: String, val message: String)

internal sealed interface DirectoryWireMessage {
    data class Snapshot(val directory: DirectoryDto) : DirectoryWireMessage
    data class Upsert(val revision: Long, val device: DirectoryEntryDto) : DirectoryWireMessage
    data class Removed(val revision: Long, val deviceId: String, val reason: RemovalReason) : DirectoryWireMessage
    data class CommandReceived(val commandId: String, val duplicate: Boolean) : DirectoryWireMessage
    data class CommandAccepted(val commandId: String) : DirectoryWireMessage
    data class CommandResult(val commandId: String, val status: CommandResultStatus, val error: CommandErrorDto?, val output: CommandResultOutputDto?) : DirectoryWireMessage
    data class CommandError(val commandId: String?, val error: CommandErrorDto) : DirectoryWireMessage
    data object Welcome : DirectoryWireMessage
    data object Registered : DirectoryWireMessage
    data object ResyncRequired : DirectoryWireMessage
    data object IgnoredUnknown : DirectoryWireMessage
}

internal enum class RemovalReason { Revoked, NoLongerPermitted, Removed }
internal enum class CommandResultStatus { Succeeded, Failed }

@Serializable internal data class CommandReceivedMessageDto(
    @SerialName("protocol_version") val protocolVersion: Int, @SerialName("message_id") val messageId: String, val type: String,
    @SerialName("sent_at") val sentAt: String, val payload: CommandReceivedPayloadDto,
)
@Serializable internal data class CommandReceivedPayloadDto(@SerialName("command_id") val commandId: String, @SerialName("received_at") val receivedAt: String, val duplicate: Boolean)
@Serializable internal data class CommandAcceptedMessageDto(
    @SerialName("protocol_version") val protocolVersion: Int, @SerialName("message_id") val messageId: String, val type: String,
    @SerialName("sent_at") val sentAt: String, val payload: CommandAcceptedPayloadDto,
)
@Serializable internal data class CommandAcceptedPayloadDto(@SerialName("command_id") val commandId: String, @SerialName("accepted_at") val acceptedAt: String)
@Serializable internal data class CommandResultMessageDto(
    @SerialName("protocol_version") val protocolVersion: Int, @SerialName("message_id") val messageId: String, val type: String,
    @SerialName("sent_at") val sentAt: String, val payload: CommandResultPayloadDto,
)
@Serializable internal data class CommandResultPayloadDto(
    @SerialName("command_id") val commandId: String, val status: String, @SerialName("completed_at") val completedAt: String,
    val error: CommandErrorDto? = null, val output: CommandResultOutputDto? = null,
)
@Serializable internal data class CommandResultOutputDto(
    val receivers: List<ChromecastReceiverDto>? = null, @SerialName("state_revision") val stateRevision: Long? = null,
)
@Serializable internal data class ChromecastReceiverDto(
    @SerialName("receiver_id") val receiverId: String, @SerialName("display_name") val displayName: String,
    @SerialName("discovered_at") val discoveredAt: String, @SerialName("expires_at") val expiresAt: String,
)
@Serializable internal data class CommandErrorDto(val code: String, val message: String, @SerialName("request_id") val requestId: String)
@Serializable internal data class WelcomeMessageDto(@SerialName("protocol_version") val protocolVersion: Int, @SerialName("message_id") val messageId: String, val type: String, @SerialName("sent_at") val sentAt: String, val payload: WelcomePayloadDto)
@Serializable internal data class WelcomePayloadDto(@SerialName("selected_protocol_version") val selectedProtocolVersion: Int)
@Serializable internal data class RegisteredMessageDto(@SerialName("protocol_version") val protocolVersion: Int, @SerialName("message_id") val messageId: String, val type: String, @SerialName("sent_at") val sentAt: String, val payload: RegisteredPayloadDto)
@Serializable internal data class RegisteredPayloadDto(@SerialName("connection_id") val connectionId: String)

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
        "command.received" -> DirectoryJson.codec.decodeFromString<CommandReceivedMessageDto>(text).payload.let { DirectoryWireMessage.CommandReceived(it.commandId, it.duplicate) }
        "command.accepted" -> DirectoryJson.codec.decodeFromString<CommandAcceptedMessageDto>(text).payload.let { DirectoryWireMessage.CommandAccepted(it.commandId) }
        "command.result" -> DirectoryJson.codec.decodeFromString<CommandResultMessageDto>(text).payload.let {
            val status = when (it.status) { "succeeded" -> CommandResultStatus.Succeeded; "failed" -> CommandResultStatus.Failed; else -> throw IllegalArgumentException("Unknown command result") }
            require((status == CommandResultStatus.Succeeded) == (it.error == null)) { "Malformed command result" }
            DirectoryWireMessage.CommandResult(it.commandId, status, it.error, it.output)
        }
        "protocol.welcome" -> DirectoryJson.codec.decodeFromString<WelcomeMessageDto>(text).also { require(it.payload.selectedProtocolVersion == 1) }.let { DirectoryWireMessage.Welcome }
        "device.registered" -> DirectoryJson.codec.decodeFromString<RegisteredMessageDto>(text).let { DirectoryWireMessage.Registered }
        "protocol.error" -> DirectoryJson.codec.decodeFromString<ProtocolErrorMessageDto>(text).payload.let {
            if (it.error.code == "directory_resync_required") DirectoryWireMessage.ResyncRequired
            else DirectoryWireMessage.CommandError(it.commandId, CommandErrorDto(it.error.code, it.error.message, "unavailable"))
        }
        else -> DirectoryJson.codec.decodeFromString<UnknownMessageDto>(text).let { DirectoryWireMessage.IgnoredUnknown }
    }
}
