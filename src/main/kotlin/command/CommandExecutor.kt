package org.bogsnebes.discordbot.command

import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import reactor.core.scheduler.Scheduler
import reactor.core.scheduler.Schedulers
import java.util.concurrent.ConcurrentHashMap

class CommandExecutor {

    private data class Invocation(
        val command: BotCommand,
        val context: CommandContext
    )

    private data class GuildQueue(
        val sink: Sinks.Many<Invocation>,
        val scheduler: Scheduler
    )

    /**
     * Ключ: guildId (asLong), 0L — для DM/без гильдии.
     */
    private val guildQueues = ConcurrentHashMap<Long, GuildQueue>()

    /**
     * Запуск команды.
     *
     * В ASYNC режиме:
     *  - любая команда выполняется сразу (Mono цепочка команды).
     *
     * В SYNC режиме:
     *  - если команда isBlocking = true → кладём её в очередь соответствующего guild;
     *  - если isBlocking = false → выполняем напрямую.
     */
    fun run(command: BotCommand, context: CommandContext): Mono<Void> {
        val guildIdOpt = context.event.interaction.guildId
        val key = guildIdOpt.map { it.asLong() }.orElse(0L) // 0L — DM / без гильдии

        val queue = guildQueues.computeIfAbsent(key) { guildKey ->
            createGuildQueue(guildKey)
        }

        val result = queue.sink.tryEmitNext(Invocation(command, context))
        return if (result.isFailure) {
            // На всякий случай fallback — выполняем напрямую
            command.execute(context)
        } else {
            // Выполнение произойдёт через подписанного consumer'а очереди
            Mono.empty()
        }
    }

    /**
     * Создаёт очередь для конкретного guild.
     * Здесь же поднимается подписчик, который:
     *  - работает на своём Scheduler (условно "поток сервера")
     *  - выполняет команды по одной через concatMap.
     */
    private fun createGuildQueue(guildKey: Long): GuildQueue {
        val sink: Sinks.Many<Invocation> =
            Sinks.many().unicast().onBackpressureBuffer()

        val scheduler: Scheduler =
            Schedulers.newSingle("guild-$guildKey")

        sink.asFlux()
            .publishOn(scheduler)     // отдельный "поток" под сервер
            .concatMap { invocation -> // строго по очереди
                invocation.command.execute(invocation.context)
            }
            .onErrorContinue { error: Throwable, _: Any? ->
                error.printStackTrace()
            }
            .doFinally {
                // Когда flux завершится — освобождаем scheduler.
                scheduler.dispose()
            }
            .subscribe()

        return GuildQueue(sink, scheduler)
    }
}
