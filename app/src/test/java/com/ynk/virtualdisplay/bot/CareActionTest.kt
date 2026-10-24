package com.ynk.virtualdisplay.bot

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CareActionTest {
    private fun care(pet: FakePet, energy: Int = 70, clean: Int = 70, maxCoins: Int = 60) = runBlocking {
        val g = pet.game
        CareAction(g, g, pet, Navigator(g, g), maxCoins = maxCoins).run(energy, clean)
    }

    @Test fun feedsAndBathesUpToTheTargets() {
        val pet = FakePet(energy = 45, cleanliness = 52)
        val result = care(pet)
        assertEquals(CareStop.DONE, result.stop)
        assertEquals(3, result.feeds)
        assertEquals(2, result.cleans)
        assertEquals(Health(75, 72), result.health)
        assertEquals(Page.ROOM, pet.game.page)
    }

    @Test fun aHealthyPetUsesNothing() {
        val pet = FakePet(energy = 90, cleanliness = 90)
        val result = care(pet)
        assertEquals(CareStop.DONE, result.stop)
        assertEquals(0, result.feeds + result.cleans)
        assertEquals(5, pet.cookies)
    }

    @Test fun anEmptyStockBuysExactlyOne() {
        val pet = FakePet(energy = 65, cleanliness = 90, cookies = 0, coins = 20)
        val result = care(pet)
        assertEquals(CareStop.DONE, result.stop)
        assertEquals(listOf(Item.COOKIE to 1), pet.purchases)
        assertEquals(15, pet.coins)
        assertEquals(5, result.coinsSpent)
    }

    @Test fun notEnoughCoinsStopsWithoutBuying() {
        val pet = FakePet(energy = 65, cleanliness = 90, cookies = 0, coins = 3)
        val result = care(pet)
        assertEquals(CareStop.PURCHASE_FAILED, result.stop)
        assertTrue(pet.purchases.isEmpty())
        assertEquals(Page.ROOM, pet.game.page)
    }

    @Test fun theCoinCapIsNeverExceeded() {
        val pet = FakePet(energy = 0, cleanliness = 90, cookies = 0, coins = 100)
        val result = care(pet, energy = 100, maxCoins = 10)
        assertEquals(CareStop.PURCHASE_FAILED, result.stop)
        assertEquals(10, result.coinsSpent)
    }

    @Test fun anItemThatChangesNothingEndsCare() {
        val pet = FakePet(energy = 40, cleanliness = 90, gainPerItem = 0)
        val result = care(pet)
        assertEquals(CareStop.NO_GAIN, result.stop)
        assertEquals(1, result.feeds)
    }

    @Test fun unreadableHealthStopsBeforeAnyItem() {
        val pet = FakePet(energy = 40, cleanliness = 40).apply { healthReadable = false }
        val result = care(pet)
        assertEquals(CareStop.HEALTH_UNREADABLE, result.stop)
        assertEquals(5, pet.cookies)
        assertEquals(Page.ROOM, pet.game.page)
    }
}
