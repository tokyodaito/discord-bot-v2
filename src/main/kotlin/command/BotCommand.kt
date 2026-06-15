package org.bogsnebes.discordbot.command

import org.bogsnebes.discordbot.data.DataLayer
import discord4j.core.event.domain.interaction.ChatInputInteractionEvent
import org.bogsnebes.discordbot.data.MusicService
import reactor.core.publisher.Mono

data class CommandContext(
    val event: ChatInputInteractionEvent,
    val data: DataLayer,
    val music: MusicService,
) {
    fun reply(content: String, ephemeral: Boolean = false): Mono<Void> {
        return if (ephemeral) {
            event.reply()
                .withEphemeral(true)
                .withContent(content)
        } else {
            event.reply(content)
        }
    }
}

interface BotCommand {
    /** Имя slash-команды: /name */
    val name: String

    /** Описание для регистрации команды в Discord */
    val description: String

    /** Основная логика команды. Reactor вместо suspend. */
    fun execute(context: CommandContext): Mono<Void>
}