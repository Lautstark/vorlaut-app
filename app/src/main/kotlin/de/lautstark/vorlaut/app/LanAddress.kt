package de.lautstark.vorlaut.app

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * The address this tablet answers on, as four numbers to be read off a screen
 * and typed into another one.
 *
 * Read from [NetworkInterface] rather than from `ConnectivityManager`, which
 * would want `ACCESS_NETWORK_STATE`. This file needs no permission at all, and
 * on a change whose whole weight is one permission being added, not adding a
 * second one is worth the few lines.
 */
object LanAddress {
    /**
     * One candidate address, kept with the interface it came from so [pick] can
     * prefer wifi without this file having to talk to the platform.
     */
    data class Candidate(
        val interfaceName: String,
        val address: String,
    )

    /** The current address, or null when this tablet is on no network. */
    fun current(): String? = pick(candidates())

    /**
     * Which of several addresses to show.
     *
     * Separated from the enumeration above so it can be tested: a machine's real
     * interfaces are whatever the machine has that day, and the interesting part
     * is the ordering, not the reading.
     *
     * `wlan` first because that is what a tablet on a home network is on, and
     * because a tablet plugged into a dock can carry an Ethernet interface whose
     * address the editor cannot reach. Otherwise the first private address, in
     * the order the platform gave them.
     */
    fun pick(candidates: List<Candidate>): String? =
        candidates.firstOrNull { it.interfaceName.startsWith("wlan") }?.address
            ?: candidates.firstOrNull()?.address

    /**
     * IPv4 and private only.
     *
     * IPv4 because the screen shows four numbers and an IPv6 address is not four
     * numbers — and because the measured path is a private v4 address, which is
     * the one Chrome exempts from mixed-content blocking. Private because a
     * public address on a tablet is either a mistake or a carrier's, and telling
     * somebody to type it into an editor would be advice to send a package
     * across the internet.
     */
    private fun candidates(): List<Candidate> =
        runCatching {
            NetworkInterface
                .getNetworkInterfaces()
                .asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { nic ->
                    nic.inetAddresses
                        .asSequence()
                        .filterIsInstance<Inet4Address>()
                        .filter { it.isSiteLocalAddress }
                        .map { Candidate(nic.name, it.hostAddress.orEmpty()) }
                }.filter { it.address.isNotEmpty() }
                .toList()
        }.getOrDefault(emptyList())
}
