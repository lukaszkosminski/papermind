package pl.lk.papermind.ai

import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpTest {
    @Test fun enginesShareConnectionPoolAndDispatcher() {
        val shared = Http.sharedClient
        val cloud = CloudEngine.defaultClient()
        val oc = OpenCodeEngine.defaultClient()
        assertSame(shared.connectionPool, cloud.connectionPool)
        assertSame(shared.dispatcher, oc.dispatcher)
        assertTrue(cloud.callTimeoutMillis != oc.callTimeoutMillis)
    }
}
