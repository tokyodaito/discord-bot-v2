package org.bogsnebes.discordbot

import discord4j.core.DiscordClient
import discord4j.core.event.domain.interaction.ChatInputInteractionEvent
import discord4j.core.`object`.command.ApplicationCommandOption
import discord4j.discordjson.json.ApplicationCommandOptionData
import discord4j.discordjson.json.ApplicationCommandRequest
import discord4j.gateway.intent.Intent
import discord4j.gateway.intent.IntentSet
import org.bogsnebes.discordbot.command.CommandDispatcher
import org.bogsnebes.discordbot.command.CommandExecutor
import org.bogsnebes.discordbot.data.InMemoryDataLayer
import org.bogsnebes.discordbot.data.MusicService
import reactor.core.publisher.Mono

fun main() {
    val token = System.getenv("DISCORD_TOKEN")
        ?: error("DISCORD_TOKEN не задан в переменных окружения")

    val client = DiscordClient.create(token)
    val gateway = client.gateway()
        .setEnabledIntents(
            IntentSet.of(
                Intent.GUILDS,
                Intent.GUILD_VOICE_STATES,
                Intent.GUILD_MESSAGES,
                Intent.DIRECT_MESSAGES,
            )
        )
        .login()
        .block()
        ?: error("Не удалось залогинить бота")

    val appId = gateway.restClient.applicationId.block()
        ?: error("Не удалось получить applicationId")

    val commands = listOf(
        ApplicationCommandRequest.builder()
            .name("ping")
            .description("Проверка бота")
            .build(),
        ApplicationCommandRequest.builder()
            .name("play")
            .description("Воспроизвести музыку с YouTube или VK")
            .addOption(
                ApplicationCommandOptionData.builder()
                    .name("url")
                    .description("YouTube/VK ссылка, YouTube запрос или vksearch:artist track")
                    .type(ApplicationCommandOption.Type.STRING.value)
                    .required(true)
                    .build()
            )
            .build()
    )

    gateway.restClient.applicationService
        .bulkOverwriteGlobalApplicationCommand(appId, commands)
        .doOnNext { cmd -> println("Registered GLOBAL command: ${cmd.name()}") }
        .doOnComplete {
            println("All global commands registered.")
            println("Discord может кэшировать глобальные команды до 1 часа.")
        }
        .blockLast()

    val executor = CommandExecutor()
    val dataLayer = InMemoryDataLayer()
    val musicService = MusicService()
    val dispatcher = CommandDispatcher(executor, dataLayer, musicService)

    gateway.on(ChatInputInteractionEvent::class.java) { event ->
        dispatcher.handle(event)
    }
        .then()
        .onErrorResume { error ->
            error.printStackTrace()
            Mono.empty()
        }
        .block()
}
