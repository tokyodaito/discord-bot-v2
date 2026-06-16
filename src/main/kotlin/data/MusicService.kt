package org.bogsnebes.discordbot.data

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManagers
import com.sedmelluq.discord.lavaplayer.source.http.HttpAudioSourceManager
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason
import com.sedmelluq.discord.lavaplayer.track.playback.MutableAudioFrame
import com.sedmelluq.discord.lavaplayer.track.playback.NonAllocatingAudioFrameBuffer
import dev.lavalink.youtube.YoutubeAudioSourceManager
import dev.lavalink.youtube.clients.AndroidVr
import dev.lavalink.youtube.clients.MWeb
import dev.lavalink.youtube.clients.Music
import dev.lavalink.youtube.clients.Tv
import dev.lavalink.youtube.clients.TvHtml5Simply
import dev.lavalink.youtube.clients.Web
import dev.lavalink.youtube.clients.WebEmbedded
import discord4j.common.util.Snowflake
import discord4j.voice.AudioProvider
import reactor.core.publisher.Mono
import reactor.core.publisher.MonoSink
import reactor.core.scheduler.Schedulers
import java.nio.ByteBuffer
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.util.Queue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.regex.Pattern

class MusicService {

    private val vkAudioResolver = VkAudioResolver()

    private val playerManager: AudioPlayerManager = DefaultAudioPlayerManager().apply {
        configuration.setFrameBufferFactory(::NonAllocatingAudioFrameBuffer)
        AudioSourceManagers.registerLocalSource(this)
        registerSourceManager(HttpAudioSourceManager())
        registerSourceManager(createYoutubeSource())
    }

    private val musicManagers = ConcurrentHashMap<Snowflake, GuildMusicManager>()

    fun getOrCreateForGuild(guildId: Snowflake): GuildMusicManager =
        musicManagers.computeIfAbsent(guildId) { GuildMusicManager(playerManager) }

    fun loadAndPlay(guildId: Snowflake, identifier: String): Mono<AudioTrack> {
        val manager = getOrCreateForGuild(guildId)

        return vkAudioResolver.resolve(identifier)
            .flatMap { playableIdentifier ->
                Mono.create { sink: MonoSink<AudioTrack> ->
                    playerManager.loadItem(playableIdentifier, object : AudioLoadResultHandler {
                        override fun trackLoaded(track: AudioTrack) {
                            manager.scheduler.play(track)
                            sink.success(track)
                        }

                        override fun playlistLoaded(playlist: AudioPlaylist) {
                            val firstTrack = playlist.selectedTrack ?: playlist.tracks.firstOrNull()
                            if (firstTrack != null) {
                                manager.scheduler.play(firstTrack)
                                sink.success(firstTrack)
                            } else {
                                sink.success()
                            }
                        }

                        override fun noMatches() {
                            sink.success()
                        }

                        override fun loadFailed(exception: FriendlyException) {
                            sink.error(exception)
                        }
                    })
                }
            }
    }

    private class VkAudioResolver {
        private val objectMapper = ObjectMapper()
        private val httpClient = HttpClient.newHttpClient()
        private val audioIdPattern = Pattern.compile("audio(-?\\d+)_(\\d+)")

        private val accessToken = System.getenv("VK_ACCESS_TOKEN")
            ?.trim()
            ?.takeIf { it.isNotBlank() }

        fun resolve(identifier: String): Mono<String> {
            if (!isVkIdentifier(identifier)) {
                return Mono.just(identifier)
            }

            return Mono.fromCallable { resolveBlocking(identifier) }
                .subscribeOn(Schedulers.boundedElastic())
        }

        private fun isVkIdentifier(identifier: String): Boolean {
            val lower = identifier.lowercase()
            return lower.startsWith("vksearch:") ||
                lower.startsWith("vk:") ||
                lower.contains("vk.com/audio") ||
                lower.contains("m.vk.com/audio")
        }

        private fun resolveBlocking(identifier: String): String {
            val token = accessToken
                ?: throw IllegalStateException("VK_ACCESS_TOKEN не задан на сервере. VK-ссылки и vksearch требуют токен VK API.")

            val audio = if (identifier.lowercase().startsWith("vksearch:") || identifier.lowercase().startsWith("vk:")) {
                val query = identifier.substringAfter(':').trim()
                if (query.isBlank()) {
                    throw IllegalArgumentException("Пустой VK-поиск. Используй `vksearch:artist track`.")
                }
                searchAudio(query, token)
            } else {
                val match = audioIdPattern.matcher(identifier)
                if (!match.find()) {
                    throw IllegalArgumentException("Не удалось распознать VK audio id. Поддерживаются ссылки вида `https://vk.com/audioOWNER_ID_AUDIO_ID`.")
                }
                getAudioById("${match.group(1)}_${match.group(2)}", token)
            }

            return audio.url
                ?: throw IllegalStateException("VK API вернул трек без прямой audio URL. Проверь права токена или попробуй другой трек.")
        }

        private fun getAudioById(audioId: String, token: String): VkAudio {
            val root = callVkApi(
                method = "audio.getById",
                params = mapOf("audios" to audioId),
                token = token,
            )

            val item = root.path("response").firstOrNull()
                ?: throw IllegalStateException("VK API не вернул трек для `$audioId`.")

            return parseAudio(item)
        }

        private fun searchAudio(query: String, token: String): VkAudio {
            val root = callVkApi(
                method = "audio.search",
                params = mapOf(
                    "q" to query,
                    "count" to "1",
                    "auto_complete" to "1",
                    "sort" to "2",
                ),
                token = token,
            )

            val response = root.path("response")
            val item = response.path("items").firstOrNull()
                ?: response.firstOrNull()
                ?: throw IllegalStateException("VK не нашёл аудио по запросу `$query`.")

            return parseAudio(item)
        }

        private fun callVkApi(method: String, params: Map<String, String>, token: String): JsonNode {
            val query = buildString {
                params.forEach { (key, value) ->
                    append(encode(key))
                    append('=')
                    append(encode(value))
                    append('&')
                }
                append("access_token=")
                append(encode(token))
                append("&v=5.131")
            }

            val request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.vk.com/method/$method?$query"))
                .GET()
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) {
                throw IllegalStateException("VK API вернул HTTP ${response.statusCode()}.")
            }

            val root = objectMapper.readTree(response.body())
            val error = root.path("error")
            if (!error.isMissingNode && !error.isNull) {
                val message = error.path("error_msg").asText("неизвестная ошибка VK API")
                throw IllegalStateException("VK API error: $message")
            }

            return root
        }

        private fun parseAudio(item: JsonNode): VkAudio {
            val artist = item.path("artist").asText("")
            val title = item.path("title").asText("")
            val url = item.path("url").asText(null)?.takeIf { it.isNotBlank() }
            return VkAudio(artist, title, url)
        }

        private fun encode(value: String): String =
            URLEncoder.encode(value, StandardCharsets.UTF_8)

        private fun JsonNode.firstOrNull(): JsonNode? {
            val elements = elements()
            return if (elements.hasNext()) elements.next() else null
        }

        private data class VkAudio(
            val artist: String,
            val title: String,
            val url: String?,
        )
    }

    private fun createYoutubeSource(): YoutubeAudioSourceManager {
        val source = YoutubeAudioSourceManager(
            MWeb(),
            Web(),
            Music(),
            WebEmbedded(),
            AndroidVr(),
            Tv(),
            TvHtml5Simply(),
        )

        val refreshToken = System.getenv("YOUTUBE_REFRESH_TOKEN")
            ?.trim()
            ?.takeIf { it.isNotBlank() }

        if (refreshToken != null) {
            source.useOauth2(refreshToken, true)
            println("YouTube OAuth enabled with refresh token.")
        } else if (System.getenv("YOUTUBE_OAUTH_INIT").equals("true", ignoreCase = true)) {
            source.useOauth2(null, false)
            println("YouTube OAuth initialization enabled. Follow the device flow in the logs, then save the printed refresh token.")
        } else {
            println("YOUTUBE_REFRESH_TOKEN is not set; age-restricted/login-required YouTube videos may fail.")
        }

        return source
    }
}

class GuildMusicManager(playerManager: AudioPlayerManager) {

    val player: AudioPlayer = playerManager.createPlayer()
    val scheduler: AudioTrackScheduler = AudioTrackScheduler(player)
    val provider: AudioProvider = LavaPlayerAudioProvider(player)

    init {
        player.addListener(scheduler)
    }
}

class AudioTrackScheduler(
    private val player: AudioPlayer
) : AudioEventAdapter() {

    private val queue: Queue<AudioTrack> = ConcurrentLinkedQueue()

    fun play(track: AudioTrack, force: Boolean = false): Boolean {
        val playing = player.startTrack(track, !force)
        if (!playing) {
            queue.offer(track)
        }
        return playing
    }

    fun skip(): Boolean {
        val next = queue.poll() ?: return false
        player.startTrack(next, false)
        return true
    }

    override fun onTrackEnd(player: AudioPlayer, track: AudioTrack, endReason: AudioTrackEndReason) {
        if (endReason.mayStartNext) {
            skip()
        }
    }
}

class LavaPlayerAudioProvider(
    private val player: AudioPlayer
) : AudioProvider(
    ByteBuffer.allocate(AudioProvider.DEFAULT_BUFFER_SIZE)
) {
    private val frame = MutableAudioFrame()

    init {
        frame.setBuffer(buffer)
    }

    override fun provide(): Boolean {
        val didProvide = player.provide(frame)
        if (didProvide) {
            buffer.flip()
        }
        return didProvide
    }
}
