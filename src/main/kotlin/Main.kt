package org.bogsnebes.discordbot

import discord4j.core.DiscordClient
import discord4j.core.event.domain.interaction.ChatInputInteractionEvent
import org.bogsnebes.discordbot.command.CommandDispatcher
import org.bogsnebes.discordbot.command.CommandExecutor
import org.bogsnebes.discordbot.data.InMemoryDataLayer


fun main() {
    val token = System.getenv("DISCORD_TOKEN")
        ?: error("Переменная окружения DISCORD_TOKEN не установлена")

    val client = DiscordClient.create(token)

    val executor = CommandExecutor()
    val dataLayer = InMemoryDataLayer()
    val dispatcher = CommandDispatcher(executor, dataLayer)

    client.login()
        .flatMap { gateway ->
            gateway.on(ChatInputInteractionEvent::class.java)
                .flatMap { event -> dispatcher.handle(event) }
                .onErrorContinue { error, _ -> error.printStackTrace() }
                .then()
        }
        .block()
}