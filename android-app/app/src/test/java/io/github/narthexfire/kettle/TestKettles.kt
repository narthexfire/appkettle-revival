package io.github.narthexfire.kettle

import io.github.narthexfire.kettle.data.KettleConfig

/** Synthetic kettles for tests. All IDs, addresses and names are made up. */
object TestKettles {
    val kitchen = KettleConfig(
        id = "kitchen", name = "Kitchen-Kettle", ip = "192.168.1.100", port = 6002,
        imei = "GD0-00000-00ab", mac = "00:26:29:00:00:01",
        wifiSsid = "KettleNet", wifiPass = "kettlepass1", autoJoinWifi = true,
    )
    val office = KettleConfig(id = "office", name = "Office-Kettle")
    val list = listOf(kitchen, office)
}
