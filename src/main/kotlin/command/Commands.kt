package org.bogsnebes.discordbot.command

import discord4j.core.event.domain.interaction.ChatInputInteractionEvent
import discord4j.core.`object`.entity.Member
import discord4j.core.`object`.entity.channel.AudioChannel
import org.bogsnebes.discordbot.data.MusicService
import reactor.core.publisher.Mono

/**
 * Простая команда, которая ходит в data-слой.
 */
class PingCommand : BotCommand {
    override val name: String = "ping"
    override val description: String = "Проверка бота"

    override fun execute(context: CommandContext): Mono<Void> {
        return context.data.getPingMessage()
            .flatMap { message -> context.reply(message) }
    }
}

class PlayCommand(
    private val musicService: MusicService
) : BotCommand {

    override val name: String = "play"
    override val description: String = "Воспроизвести музыку с YouTube"

    override fun execute(context: CommandContext): Mono<Void> {
        val event: ChatInputInteractionEvent = context.event

        val input = event.getOption("url")
            .flatMap { it.value }
            .map { it.asString().trim() }
            .filter { it.isNotBlank() }
            .orElse(null)

        if (input == null) {
            return event.reply()
                .withEphemeral(true)
                .withContent("Нужно передать ссылку или поисковый запрос YouTube: `/play url:<ссылка или название>`")
                .then()
        }

        val guildId = event.interaction.guildId.orElse(null)
        if (guildId == null) {
            return event.reply()
                .withEphemeral(true)
                .withContent("Команда `/play` доступна только на сервере, не в личных сообщениях.")
                .then()
        }

        val member: Member = event.interaction.member.orElse(null)
            ?: return event.reply()
                .withEphemeral(true)
                .withContent("Не удалось определить участника, вызвавшего команду.")
                .then()

        val identifier = normalizeYoutubeIdentifier(input)

        return event.deferReply()
            .then(
                member.voiceState
                    .flatMap { it.channel }
                    .cast(AudioChannel::class.java)
                    .switchIfEmpty(
                        editReply(event, "Сначала зайди в голосовой канал, потом используй `/play`.")
                            .then(Mono.empty())
                    )
                    .flatMap { channel ->
                        val guildMusic = musicService.getOrCreateForGuild(guildId)

                        channel.join { spec -> spec.setProvider(guildMusic.provider) }
                            .then(
                                musicService.loadAndPlay(guildId, identifier)
                                    .flatMap { track ->
                                        val title = track.info.title
                                        val duration = formatDuration(track.info.length)
                                        editReply(event, "▶ Воспроизвожу: **$title** `[$duration]`")
                                    }
                                    .switchIfEmpty(
                                        editReply(event, "Не удалось найти аудио на YouTube по этому запросу.")
                                    )
                            )
                    }
            )
            .onErrorResume { error ->
                editReply(
                    event,
                    "Не удалось воспроизвести трек: ${error.message ?: "неизвестная ошибка"}"
                )
            }
    }

    private fun editReply(event: ChatInputInteractionEvent, content: String): Mono<Void> {
        return event.editReply(content).then()
    }

    private fun normalizeYoutubeIdentifier(input: String): String {
        val lower = input.lowercase()
        return when {
            lower.startsWith("http://") -> input
            lower.startsWith("https://") -> input
            lower.startsWith("ytsearch:") -> input
            lower.startsWith("ytmsearch:") -> input
            else -> "ytsearch:$input"
        }
    }

    private fun formatDuration(lengthMs: Long): String {
        if (lengthMs <= 0) return "LIVE"
        val totalSeconds = lengthMs / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60

        return if (hours > 0) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%d:%02d".format(minutes, seconds)
        }
    }
}
