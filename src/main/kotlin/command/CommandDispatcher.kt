package org.bogsnebes.discordbot.command

import discord4j.core.event.domain.interaction.ChatInputInteractionEvent
import org.bogsnebes.discordbot.data.DataLayer
import reactor.core.publisher.Mono

/**
 * Роутер: по имени slash-команды выбирает Command и отдаёт её в Executor.
 */
class CommandDispatcher(
    private val executor: CommandExecutor,
    private val dataLayer: DataLayer
) {

    private val commands: Map<String, BotCommand> = buildCommands()

    private fun buildCommands(): Map<String, BotCommand> {
        val list = listOf(
            PingCommand(),
            // сюда потом добавишь музыкальные команды
        )
        return list.associateBy { it.name }
    }

    fun handle(event: ChatInputInteractionEvent): Mono<Void> {
        val command = commands[event.commandName]
        if (command == null) {
            return event.reply()
                .withEphemeral(true)
                .withContent("Неизвестная команда: /${event.commandName}")
        }

        val context = CommandContext(
            event = event,
            data = dataLayer
        )

        return executor.run(command, context)
    }
}