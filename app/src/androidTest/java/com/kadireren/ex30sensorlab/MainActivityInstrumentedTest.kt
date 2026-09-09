package com.kadireren.ex30sensorlab

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityInstrumentedTest {
    @Test fun navigatesAcrossThreeMainScreens() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withText("AAOS Verileri")).perform(click())
            onView(withText("AAOS Verileri")).check(matches(withText("AAOS Verileri")))
            onView(withText("‹ Ana menü")).perform(click())

            onView(withText("OBD Verileri")).perform(click())
            onView(withText("Download'a aktar")).check(matches(withText("Download'a aktar")))
            onView(withText("‹ Ana menü")).perform(click())

            onView(withText("Sensör Keşfi")).perform(click())
            onView(withText("HCI kayıt rehberini göster")).check(matches(withText("HCI kayıt rehberini göster")))
        }
    }
}
