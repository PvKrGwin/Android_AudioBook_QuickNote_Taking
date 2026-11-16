package com.praveen.ridernotes.desktop

import java.awt.Color
import java.awt.Font
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.RenderingHints
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

class DesktopTrayController(
    private val onOpen: () -> Unit,
    private val onAddMediaNote: () -> Unit,
    private val onExit: () -> Unit
) : AutoCloseable {
    private var icon: TrayIcon? = null

    fun install(): Boolean {
        if (!SystemTray.isSupported()) return false
        val menu = PopupMenu().apply {
            add(MenuItem("Add media note").apply { addActionListener { onAddMediaNote() } })
            add(MenuItem("Open Rider Notes").apply { addActionListener { onOpen() } })
            addSeparator()
            add(MenuItem("Exit").apply { addActionListener { onExit() } })
        }
        val trayIcon = TrayIcon(loadImage(), "Rider Notes", menu).apply {
            isImageAutoSize = true
            addActionListener { onAddMediaNote() }
        }
        SystemTray.getSystemTray().add(trayIcon)
        icon = trayIcon
        return true
    }

    fun notify(title: String, message: String) {
        icon?.displayMessage(title, message, TrayIcon.MessageType.INFO)
    }

    override fun close() {
        icon?.let { SystemTray.getSystemTray().remove(it) }
        icon = null
    }

    private fun loadImage(): BufferedImage = runCatching {
        requireNotNull(javaClass.getResourceAsStream("/rider_notes_logo.png")).use {
            ImageIO.read(it)
        }
    }.getOrElse { createFallbackImage() }

    private fun createFallbackImage(): BufferedImage = BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB).also { image ->
        val graphics = image.createGraphics()
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        graphics.color = Color(0x1F, 0x6D, 0x86)
        graphics.fillOval(2, 2, 60, 60)
        graphics.color = Color.WHITE
        graphics.font = Font("SansSerif", Font.BOLD, 25)
        val text = "RN"
        val metrics = graphics.fontMetrics
        graphics.drawString(text, (64 - metrics.stringWidth(text)) / 2, 40)
        graphics.dispose()
    }
}
