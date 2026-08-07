package com.adskipper.core.detect

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * L1: scan the accessibility node tree for a "skip" button (<10ms).
 */
object NodeMatcher {

    fun findSkipNode(root: AccessibilityNodeInfo?, keywords: Collection<String>): Rect? {
        root ?: return null
        val clickableHits = ArrayList<Rect>()
        val otherHits = ArrayList<Rect>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.isVisibleToUser) {
                val textMatch =
                    KeywordMatcher.matches(node.text?.toString(), keywords)
                        ?.takeIf { KeywordMatcher.isPlausibleButtonText(node.text?.toString()) }
                val descMatch =
                    KeywordMatcher.matches(node.contentDescription?.toString(), keywords)
                        ?.takeIf { KeywordMatcher.isPlausibleButtonText(node.contentDescription?.toString()) }
                val idMatch = KeywordMatcher.matches(
                    node.viewIdResourceName?.substringAfterLast('/'), keywords)

                if (textMatch != null || descMatch != null || idMatch != null) {
                    val bounds = Rect()
                    node.getBoundsInScreen(bounds)
                    if (!bounds.isEmpty) {
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
}
