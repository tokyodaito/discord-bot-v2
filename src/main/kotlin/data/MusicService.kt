package org.bogsnebes.discordbot.data

import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManagers
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
import java.nio.ByteBuffer
import java.util.Queue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

class MusicService {

    private val playerManager: AudioPlayerManager = DefaultAudioPlayerManager().apply {
        configuration.setFrameBufferFactory(::NonAllocatingAudioFrameBuffer)
        AudioSourceManagers.registerLocalSource(this)
        registerSourceManager(createYoutubeSource())
    }

    private val musicManagers = ConcurrentHashMap<Snowflake, GuildMusicManager>()

    fun getOrCreateForGuild(guildId: Snowflake): GuildMusicManager =
        musicManagers.computeIfAbsent(guildId) { GuildMusicManager(playerManager) }

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
