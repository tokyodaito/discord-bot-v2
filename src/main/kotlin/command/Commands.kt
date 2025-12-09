package org.bogsnebes.discordbot.command

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