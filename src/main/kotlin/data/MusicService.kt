package org.bogsnebes.discordbot.data

import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManagers
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason
import com.sedmelluq.discord.lavaplayer.track.playback.MutableAudioFrame
import com.sedmelluq.discord.lavaplayer.track.playback.NonAllocatingAudioFrameBuffer
import dev.lavalink.youtube.YoutubeAudioSourceManager
import discord4j.common.util.Snowflake
import discord4j.voice.AudioProvider
import reactor.core.publisher.Mono
import reactor.core.publisher.MonoSink
import java.nio.ByteBuffer
import java.util.Queue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Глобальный сервис музыки: один AudioPlayerManager на всё приложение
 * и по одному GuildMusicManager на каждый сервер.
 */
class MusicService {

    private val playerManager: AudioPlayerManager = DefaultAudioPlayerManager().apply {
        // Оптимизация буфера для Discord4J
        configuration.setFrameBufferFactory(::NonAllocatingAudioFrameBuffer)

        // Локальные источники опциональны, можешь убрать эту строчку, если не планируешь файловую систему
        AudioSourceManagers.registerLocalSource(this)

        // Регистрируем youtube-source вместо встроенного youtube Lavaplayer
        val yt = YoutubeAudioSourceManager()
        registerSourceManager(yt)
        // ВАЖНО: НЕ вызываем AudioSourceManagers.registerRemoteSources(this),
        // чтобы не подмешивать старый YoutubeAudioSourceManager
    }

    private val musicManagers = ConcurrentHashMap<Snowflake, GuildMusicManager>()

    fun getOrCreateForGuild(guildId: Snowflake): GuildMusicManager =
        musicManagers.computeIfAbsent(guildId) { GuildMusicManager(playerManager) }

    /**
     * Загружает трек (YouTube URL/ytsearch и т.п.), ставит его в очередь/проигрывание
     * и возвращает Mono, который завершится:
     *  - с AudioTrack при успехе
     *  - пустым Mono, если ничего не найдено
     *  - с ошибкой при loadFailed.
     */
    fun loadAndPlay(guildId: Snowflake, identifier: String): Mono<AudioTrack> {
        val manager = getOrCreateForGuild(guildId)

        return Mono.create { sink: MonoSink<AudioTrack> ->
            playerManager.loadItem(identifier, object : AudioLoadResultHandler {
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
                        sink.success() // пустой результат
                    }
                }

                override fun noMatches() {
                    sink.success() // пустой результат
                }

                override fun loadFailed(exception: FriendlyException) {
                    sink.error(exception)
                }
            })
        }
    }
}

/**
 * Набор объектов аудио для одного guild: player + очередь + AudioProvider.
 */
class GuildMusicManager(playerManager: AudioPlayerManager) {

    val player: AudioPlayer = playerManager.createPlayer()
    val scheduler: AudioTrackScheduler = AudioTrackScheduler(player)
    val provider: AudioProvider = LavaPlayerAudioProvider(player)

    init {
        player.addListener(scheduler)
    }
}

/**
 * Очередь треков + автопереключение на следующий.
 */
class AudioTrackScheduler(
    private val player: AudioPlayer
) : com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter() {

    private val queue: Queue<AudioTrack> = ConcurrentLinkedQueue()

    fun play(track: AudioTrack, force: Boolean = false): Boolean {
        // startTrack(track, true) — играть только если сейчас ничего не играет
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

/**
 * Мост между Lavaplayer и Discord4J: отдаёт opus-фреймы в голос.
 */
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