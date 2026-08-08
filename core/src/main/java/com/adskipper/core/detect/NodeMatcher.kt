package com.adskipper.core.detect

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import timber.log.Timber

/**
 * L1: scan the accessibility node tree for a "skip" button (<10ms).
 */
object NodeMatcher {

    fun findSkipNode(
        root: AccessibilityNodeInfo?,
        keywords: Collection<String>,
        excluded: Collection<String> = emptySet(),
    ): Rect? {
        root ?: return null
        // A keyword match whose bounds cover (nearly) the whole screen is
        // never the skip button — e.g. Douban's `id/skip` is a full-screen,
        // text-less container; tapping its center hits the ad click-through
        // area. Real skip buttons are small.
        val rootBounds = Rect().also { root.getBoundsInScreen(it) }
        val maxArea = rootBounds.width() * rootBounds.height() / 3
        val clickableHits = ArrayList<Rect>()
        val otherHits = ArrayList<Rect>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.isVisibleToUser) {
                val textMatch =
                    KeywordMatcher.matches(node.text?.toString(), keywords, excluded)
                        ?.takeIf { KeywordMatcher.isPlausibleButtonText(node.text?.toString()) }
                val descMatch =
                    KeywordMatcher.matches(node.contentDescription?.toString(), keywords, excluded)
                        ?.takeIf { KeywordMatcher.isPlausibleButtonText(node.contentDescription?.toString()) }
                val idMatch = KeywordMatcher.matches(
                    node.viewIdResourceName?.substringAfterLast('/'), keywords)

                if (textMatch != null || descMatch != null || idMatch != null) {
                    val bounds = Rect()
                    node.getBoundsInScreen(bounds)
                    if (!bounds.isEmpty && bounds.width() * bounds.height() <= maxArea) {
                        Timber.d(
                            "L1 match text=%s desc=%s id=%s clickable=%b bounds=%s",
                            node.text, node.contentDescription,
                            node.viewIdResourceName, node.isClickable, bounds,
                        )
                        (if (node.isClickable) clickableHits else otherHits).add(bounds)
                    }
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let(queue::addLast)
            }
        }
        // Prefer explicitly clickable nodes; a tap gesture at the bounds
        // center works even when the node itself is not marked clickable.
        return clickableHits.firstOrNull() ?: otherHits.firstOrNull()
    }

    /** Node count with an early exit at [cap]. Splash/ad screens are a
     *  handful of views (the ad SDK's container), while real app UI is
     *  hundreds — used to decide whether a screen can still be a splash ad
     *  after the core splash window has elapsed. */
    fun treeSize(root: AccessibilityNodeInfo?, cap: Int): Int {
        root ?: return 0
        var count = 0
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (++count >= cap) return count
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let(queue::addLast)
            }
        }
        return count
    }
}
