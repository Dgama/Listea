package com.example.listea

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The app can start.
 *
 * This exists because nothing else in the suite could have failed for the reason it did. Every other
 * test constructs its subject directly, which is the one way the app never does: at runtime the
 * ViewModel comes out of `ViewModelProvider`, and for an `AndroidViewModel` that means a reflective
 * `modelClass.getConstructor(Application::class.java)`. Adding defaulted constructor parameters
 * deletes that overload — Kotlin keeps only the full constructor and a synthetic bitmask one — and
 * the lookup then throws `NoSuchMethodException` from inside `setContent`, so the app dies on launch
 * before it renders anything. It compiles, it passes every other test, and it is broken.
 *
 * Going through the real factory rather than asserting the signature exists is the point: the
 * failure was never that the class was unconstructible, it was that it was unconstructible *that
 * way*.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidFactoryTest {

    private val application: Application
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `the ViewModel Android's own factory looks up can be created`() {
        val owner = object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }

        val created = ViewModelProvider(
            owner,
            ViewModelProvider.AndroidViewModelFactory.getInstance(application)
        ).get(ListsViewModel::class.java)

        assertNotNull(created)
    }
}
