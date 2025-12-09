package org.bogsnebes.discordbot.data

import reactor.core.publisher.Mono

interface DataLayer {
    fun getPingMessage(): Mono<String>
}

class InMemoryDataLayer : DataLayer {
    override fun getPingMessage(): Mono<String> {
        return Mono.just("Pong из data-слоя (Reactor)!")
    }
}