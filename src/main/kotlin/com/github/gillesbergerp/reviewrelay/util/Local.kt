package com.github.gillesbergerp.reviewrelay.util

/**
 * The loopback address, spelled numerically.
 *
 * Not "localhost": that resolves to ::1 first on a dual-stack machine, and the servers this plugin
 * talks to bind 127.0.0.1 only.
 */
const val LOOPBACK = "127.0.0.1"

/** The base of a URL on this machine, for a server that named a port and nothing else. */
fun localUrl(port: Int): String = "http://$LOOPBACK:$port"
