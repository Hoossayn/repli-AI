package dev.patrickgold.florisboard.repli.openai

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

private fun style(vararg examples: String, summary: String? = null) = PromptStyle("casual", summary, examples.toList())
private fun me(text: String) = PromptTurn(true, text)
private fun them(text: String) = PromptTurn(false, text)

class ReplyPromptBuilderTest {
    private val prompt = ReplyPrompt(
        context = listOf(them("good morning!! you around later?"), me("yeah should be")),
        style = style("lol yeah", "omw"),
        history = listOf(them("hey"), me("hey hey")),
        instructions = "keep it short",
        timeZoneId = "Africa/Lagos",
    )

    @Test fun replyBodyMatchesBackendShape() {
        val body = ReplyPromptBuilder.reply(prompt, "gpt-6.1-sol", 1_760_000_000L)
        assertEquals("gpt-6.1-sol", body.getString("model"))
        assertFalse(body.getBoolean("store"))
        assertTrue(body.getBoolean("stream"))
        assertFalse(body.has("reasoning"))
        assertEquals(800, body.getInt("max_output_tokens"))
        val format = body.getJSONObject("text").getJSONObject("format")
        assertEquals("json_schema", format.getString("type"))
        assertEquals("reply_candidates", format.getString("name"))
        val item = body.getJSONArray("input").getJSONObject(0)
        assertEquals("user", item.getString("role"))
        val input = JSONObject(item.getString("content"))
        assertEquals(2, input.getJSONArray("context").length())
        assertEquals("them", input.getJSONArray("context").getJSONObject(0).getString("speaker"))
        assertEquals(2, input.getJSONArray("history").length())
        assertEquals("keep it short", input.getString("instructions"))
        val situation = input.getJSONObject("replySituation")
        assertEquals("reply", situation.getString("intent"))
        assertEquals("Africa/Lagos", situation.getString("timeZone"))
        assertEquals("2025-10-09", situation.getString("localDate"))
        assertEquals("09:53", situation.getString("localTime"))
        assertEquals("2025-10-09T08:53:20Z", situation.getString("currentTimeUtc"))
        val instructions = body.getString("instructions")
        assertTrue(instructions.startsWith("Write exactly three distinct candidate messages"))
        assertTrue("By default, reply to the current chat" in instructions)
        assertTrue("little or no evidence of emoji use" in instructions)
    }

    @Test fun freshStartAllowsEmptyContextAndChangesGuidance() {
        val body = ReplyPromptBuilder.reply(prompt.copy(context = emptyList(), intent = PromptIntent.FRESH_START), "m", 0)
        assertTrue("start a fresh conversation" in body.getString("instructions"))
        assertEquals(0, JSONObject(body.getJSONArray("input").getJSONObject(0).getString("content")).getJSONArray("context").length())
        try { ReplyPromptBuilder.reply(prompt.copy(context = emptyList()), "m", 0); fail("reply needs context") } catch (_: IllegalArgumentException) {}
    }

    @Test fun styleGuidanceFollowsOutgoingHabits() {
        val emojiHeavy = prompt.copy(history = listOf(me("yes 😂"), me("okay 🙏"), me("lol 😭 — fine")), context = listOf(them("hi"), me("hey.")))
        val guidance = ReplyPromptBuilder.writingStyleGuidance(emojiHeavy)
        assertTrue("Emoji appear regularly" in guidance)
        assertTrue("Match the user's actual use of em dashes" in guidance)
        assertTrue("usually omits final punctuation" in guidance)
        val fromSummary = prompt.copy(history = emptyList(), context = listOf(them("hi")),
            style = style(summary = "sometimes uses emojis; rarely ends with punctuation"))
        val summaryGuidance = ReplyPromptBuilder.writingStyleGuidance(fromSummary)
        assertTrue("only occasionally uses emoji" in summaryGuidance)
        assertTrue("usually omits final punctuation" in summaryGuidance)
    }

    @Test fun unknownTimeZoneFallsBackToUtc() {
        val situation = ReplyPromptBuilder.replySituationJson(prompt.copy(timeZoneId = "Mars/Olympus"), 0)
        assertEquals("1970-01-01", situation.getString("localDate"))
        assertEquals("00:00", situation.getString("localTime"))
        val fixed = ReplyPromptBuilder.replySituationJson(prompt.copy(timeZoneId = "GMT+01:00"), 0)
        assertEquals("01:00", fixed.getString("localTime"))
    }

    @Test fun personaAndOptionsAreSerialised() {
        val persona = PromptPersona("Chill", "Laid back", listOf("a", "b", "c", "d"))
        val body = ReplyPromptBuilder.reply(prompt.copy(style = prompt.style.copy(persona = persona)), "m", 0,
            PromptOptions(reasoningEffort = "low", verbosity = "low"))
        assertEquals("low", body.getJSONObject("reasoning").getString("effort"))
        assertEquals("low", body.getJSONObject("text").getString("verbosity"))
        val personaJson = JSONObject(body.getJSONArray("input").getJSONObject(0).getString("content")).getJSONObject("style").getJSONObject("persona")
        assertEquals(3, personaJson.getJSONArray("examples").length())
        val plain = ReplyPromptBuilder.reply(prompt, "m", 0, PromptOptions(structuredOutput = false, stream = false))
        assertFalse(plain.has("text"))
        assertFalse(plain.getBoolean("stream"))
        assertTrue(plain.getString("instructions").endsWith("and nothing else."))
    }

    @Test fun visionBodyCarriesEveryImageInOrder() {
        val body = ReplyPromptBuilder.vision(listOf("AAA", "BBB"), "image/png", "m")
        val content = body.getJSONArray("input").getJSONObject(0).getJSONArray("content")
        assertEquals(3, content.length())
        assertTrue(content.getJSONObject(0).getString("text").startsWith("These 2 screenshots are indexed 0 through 1"))
        assertEquals("data:image/png;base64,BBB", content.getJSONObject(2).getString("image_url"))
        assertEquals("visual_bubbles", body.getJSONObject("text").getJSONObject("format").getString("name"))
        assertEquals(8_000, body.getInt("max_output_tokens"))
        try { ReplyPromptBuilder.vision(emptyList(), "image/png", "m"); fail() } catch (_: IllegalArgumentException) {}
    }

    @Test fun rewriteBodyUsesClosedToneTable() {
        val body = ReplyPromptBuilder.rewrite(RewritePrompt("pls send d money", "polite", style("ok")), "m")
        assertTrue(body.getString("instructions").endsWith("Tone for this rewrite: Make it polite and respectful, suitable for someone the user does not know well or a work contact."))
        assertEquals("rewrite_candidates", body.getJSONObject("text").getJSONObject("format").getString("name"))
        assertEquals("pls send d money", JSONObject(body.getJSONArray("input").getJSONObject(0).getString("content")).getString("text"))
        try { ReplyPromptBuilder.rewrite(RewritePrompt("x", "sarcastic", style()), "m"); fail() } catch (_: IllegalArgumentException) {}
    }
}

class OpenAiResponseParsingTest {
    private fun completed(text: String, status: String = "completed") = JSONObject()
        .put("id", "resp_1").put("status", status).put("model", "gpt-x")
        .put("usage", JSONObject().put("input_tokens", 12).put("output_tokens", 7))
        .put("output", JSONArray().put(JSONObject().put("type", "message").put("content",
            JSONArray().put(JSONObject().put("type", "output_text").put("text", text)))))

    @Test fun parsesPlainJsonResponse() {
        val envelope = OpenAiResponseParsing.finishedResponse(completed("""{"candidates":["a","b","c"]}""").toString())
        assertEquals(OpenAiUsage(12, 7), envelope.usage)
        assertEquals(listOf("a", "b", "c"), OpenAiResponseParsing.candidates(envelope.response, 500))
    }

    @Test fun parsesEventStreamAndTakesCompletedEvent() {
        val stream = buildString {
            append("event: response.created\ndata: {\"type\":\"response.created\",\"response\":{\"status\":\"in_progress\"}}\n\n")
            append("event: response.output_text.delta\ndata: {\"type\":\"response.output_text.delta\",\"delta\":\"{\\\"cand\"}\n\n")
            append("event: response.completed\ndata: ")
            append(JSONObject().put("type", "response.completed").put("response", completed("""{"candidates":["x","y","z"]}""")).toString())
            append("\n\ndata: [DONE]\n\n")
        }
        val envelope = OpenAiResponseParsing.finishedResponse(stream)
        assertEquals(listOf("x", "y", "z"), OpenAiResponseParsing.candidates(envelope.response, 500))
    }

    @Test fun streamFailureSurfacesCode() {
        val stream = "event: response.failed\ndata: " + JSONObject().put("type", "response.failed").put("response",
            JSONObject().put("status", "failed").put("error", JSONObject().put("code", "server_error"))).toString() + "\n\n"
        try { OpenAiResponseParsing.finishedResponse(stream); fail() } catch (e: OpenAiException) {
            assertEquals(OpenAiFailure.UNAVAILABLE, e.failure)
            assertEquals("server_error", e.apiCode)
        }
        try { OpenAiResponseParsing.finishedResponse("event: x\ndata: {\"type\":\"response.created\"}\n\n"); fail() } catch (e: OpenAiException) {
            assertEquals(OpenAiFailure.INVALID_RESPONSE, e.failure)
        }
    }

    @Test fun incompleteAndRefusalMap() {
        val incomplete = completed("{}", status = "incomplete").put("incomplete_details", JSONObject().put("reason", "max_output_tokens"))
        try { OpenAiResponseParsing.finishedResponse(incomplete.toString()); fail() } catch (e: OpenAiException) { assertEquals(OpenAiFailure.INCOMPLETE, e.failure) }
        val filtered = completed("{}", status = "incomplete").put("incomplete_details", JSONObject().put("reason", "content_filter"))
        try { OpenAiResponseParsing.finishedResponse(filtered.toString()); fail() } catch (e: OpenAiException) { assertEquals(OpenAiFailure.REFUSED, e.failure) }
        val refusal = completed("x").put("output", JSONArray().put(JSONObject().put("content", JSONArray().put(JSONObject().put("type", "refusal").put("refusal", "no")))))
        try { OpenAiResponseParsing.finishedResponse(refusal.toString()); fail() } catch (e: OpenAiException) { assertEquals(OpenAiFailure.REFUSED, e.failure) }
    }

    @Test fun candidateValidationIsStrict() {
        fun check(text: String) = try { OpenAiResponseParsing.candidates(completed(text), 500); false } catch (e: OpenAiException) { e.failure == OpenAiFailure.INVALID_RESPONSE }
        assertTrue(check("""{"candidates":["a","b"]}"""))
        assertTrue(check("""{"candidates":["a","a","b"]}"""))
        assertTrue(check("""{"candidates":["a"," ","b"]}"""))
        assertTrue(check("not json"))
        assertEquals(listOf("a", "b", "c"), OpenAiResponseParsing.candidates(completed("Sure! ```json\n{\"candidates\":[\"a\",\"b\",\"c\"]}\n```"), 500))
    }

    @Test fun visualCaptureIsValidatedSortedAndCleaned() {
        fun bubble(frame: Int, speaker: String, text: String, top: Int) = JSONObject().put("frame_index", frame).put("speaker", speaker).put("text", text)
            .put("box", JSONObject().put("left", 10).put("top", top).put("right", 900).put("bottom", top + 50))
        val payload = JSONObject().put("contact_name", "  Ada   Lovelace ").put("frame_order", JSONArray(listOf(0, 1)))
            .put("bubbles", JSONArray()
                .put(bubble(1, "me", "see you at 2:30 pm ✓✓", 100))
                .put(bubble(0, "them", "12/03/2024", 50))
                .put(bubble(0, "them", "Add contact", 400))
                .put(bubble(0, "them", "Tunde Bakare", 300))
                .put(bubble(0, "me", "hey 10:21 ✓✓", 200))
                .put(bubble(0, "them", "2:30", 250)))
        val capture = OpenAiResponseParsing.visualCapture(completed(payload.toString()), 2)
        assertEquals("Ada Lovelace", capture.contactName)
        assertEquals(listOf("hey", "2:30", "see you at"), capture.bubbles.map { it.text })
        assertEquals(listOf(0, 0, 1), capture.bubbles.map { it.frameIndex })
        assertTrue(capture.bubbles[0].fromMe)
        assertNull(OpenAiResponseParsing.visualCapture(completed(payload.put("contact_name", "WhatsApp").toString()), 2).contactName)
        val outOfRange = JSONObject().put("contact_name", JSONObject.NULL).put("frame_order", JSONArray(listOf(0))).put("bubbles", JSONArray().put(bubble(3, "me", "x", 1)))
        try { OpenAiResponseParsing.visualCapture(completed(outOfRange.toString()), 1); fail() } catch (e: OpenAiException) { assertEquals(OpenAiFailure.INVALID_RESPONSE, e.failure) }
    }

    @Test fun cleanVisionTextDropsMetadataLines() {
        assertNull(OpenAiResponseParsing.cleanVisionText("10:21 ✓✓"))
        assertEquals("10:21", OpenAiResponseParsing.cleanVisionText("10:21", allowStandaloneTime = true))
        assertNull(OpenAiResponseParsing.cleanVisionText("March 3, 2024"))
        assertEquals("ok see you", OpenAiResponseParsing.cleanVisionText("ok  see\nyou 9:15 pm ✓"))
    }
}

class OpenAiPlanClientTest {
    private class FakeTransport(private val handler: (OpenAiHttpRequest) -> OpenAiHttpResponse) : OpenAiTransport {
        val requests = ArrayList<OpenAiHttpRequest>()
        override suspend fun send(request: OpenAiHttpRequest): OpenAiHttpResponse { requests += request; return handler(request) }
    }

    private fun models(vararg entries: Pair<String, String>) = OpenAiHttpResponse(200, JSONObject().put("data", JSONArray().apply {
        entries.forEach { (slug, visibility) -> put(JSONObject().put("id", "id-$slug").put("slug", slug).put("visibility", visibility)) }
    }).toString())

    private fun ok(text: String) = OpenAiHttpResponse(200, JSONObject().put("id", "resp_9").put("status", "completed").put("model", "gpt-6.1-sol")
        .put("output", JSONArray().put(JSONObject().put("content", JSONArray().put(JSONObject().put("type", "output_text").put("text", text))))).toString())

    private fun error(status: Int, code: String, param: String? = null) =
        OpenAiHttpResponse(status, JSONObject().put("error", JSONObject().put("code", code).put("message", "secret detail").apply { param?.let { put("param", it) } }).toString())

    private val prompt = ReplyPrompt(listOf(them("hi")), style("yo"))

    @Test fun planPicksFirstListedModelAndCachesIt() = runBlocking {
        var now = 1_000L
        val transport = FakeTransport { request ->
            if (request.url.endsWith("/models")) models("gpt-6.1-sol-hidden" to "hidden", "gpt-6.1-sol" to "list", "gpt-6.1-nova" to "list")
            else ok("""{"candidates":["a","b","c"]}""")
        }
        val client = OpenAiPlanClient(transport, "https://example.test/v1", clock = { now })
        val credential = OpenAiCredential.ChatGptPlan("tok")
        val result = client.generateReplies(credential, prompt)
        assertEquals(listOf("a", "b", "c"), result.value)
        val planBody = JSONObject(String(transport.requests[1].body!!))
        assertEquals("gpt-6.1-sol", planBody.getString("model"))
        assertFalse(planBody.has("max_output_tokens"), "plan route rejects max_output_tokens")
        assertTrue(planBody.getBoolean("stream") && !planBody.getBoolean("store"))
        assertEquals("Bearer tok", transport.requests[1].headers["Authorization"])
        client.generateReplies(credential, prompt)
        assertEquals(3, transport.requests.size, "model list is cached")
        now += 4_000
        client.generateReplies(credential, prompt)
        assertEquals(5, transport.requests.size, "cache expires after an hour")
        assertEquals("gpt-6.1-nova", client.resolveModel(OpenAiCredential.ChatGptPlan("tok", preferredModel = "gpt-6.1-nova")))
    }

    @Test fun apiKeyUsesConfiguredModelWithoutListing() = runBlocking {
        val transport = FakeTransport { ok("""{"candidates":["a","b","c"]}""") }
        val client = OpenAiPlanClient(transport, "https://example.test/v1")
        client.rewrite(OpenAiCredential.ApiKey("sk-test", "gpt-5-mini"), RewritePrompt("hey", "casual", style()))
        assertEquals(1, transport.requests.size)
        val keyBody = JSONObject(String(transport.requests[0].body!!))
        assertEquals("gpt-5-mini", keyBody.getString("model"))
        assertEquals(700, keyBody.getInt("max_output_tokens"))
    }

    @Test fun subscriptionSharingErrorsMapToClosedFailures() = runBlocking {
        val cases = listOf(
            error(429, "subscription_sharing_usage_limit_exceeded") to OpenAiFailure.PLAN_USAGE_LIMIT,
            error(503, "subscription_sharing_usage_unavailable") to OpenAiFailure.PLAN_UNAVAILABLE,
            error(503, "subscription_sharing_user_unavailable") to OpenAiFailure.PLAN_UNAVAILABLE,
            error(403, "subscription_sharing_user_not_eligible") to OpenAiFailure.PLAN_NOT_ELIGIBLE,
            error(403, "subscription_sharing_route_not_supported") to OpenAiFailure.PLAN_UNSUPPORTED,
            error(403, "chatpass_v2_scope_not_authorized") to OpenAiFailure.SIGN_IN_REQUIRED,
            error(401, "subscription_sharing_invalid_user") to OpenAiFailure.SIGN_IN_REQUIRED,
            error(429, "insufficient_quota") to OpenAiFailure.API_KEY_QUOTA,
            error(429, "rate_limit_exceeded") to OpenAiFailure.RATE_LIMITED,
            error(404, "model_not_found") to OpenAiFailure.NO_MODEL,
            error(400, "invalid_request") to OpenAiFailure.REQUEST_REJECTED,
            error(500, "server_error") to OpenAiFailure.UNAVAILABLE,
            OpenAiHttpResponse(502, "<html>bad gateway</html>") to OpenAiFailure.UNAVAILABLE,
        )
        for ((response, expected) in cases) {
            val client = OpenAiPlanClient(FakeTransport { response }, "https://example.test/v1")
            try {
                client.rewrite(OpenAiCredential.ApiKey("k", "m"), RewritePrompt("hey", "casual", style()))
                fail("expected $expected")
            } catch (e: OpenAiException) {
                assertEquals(expected, e.failure, "status ${response.status}")
                assertFalse("secret detail" in (e.message ?: ""))
            }
        }
    }

    @Test fun unsupportedCapabilityDropsTheParameterAndRetries() = runBlocking {
        val transport = FakeTransport { request ->
            val body = JSONObject(String(request.body!!))
            when {
                body.has("reasoning") -> error(400, "subscription_sharing_unsupported_capability", "reasoning.effort")
                body.has("text") -> error(400, "subscription_sharing_unsupported_capability", "text.format")
                else -> ok("here you go {\"candidates\":[\"a\",\"b\",\"c\"]}")
            }
        }
        val client = OpenAiPlanClient(transport, "https://example.test/v1", defaultOptions = PromptOptions(reasoningEffort = "low"))
        val result = client.rewrite(OpenAiCredential.ApiKey("k", "m"), RewritePrompt("hey", "casual", style()))
        assertEquals(listOf("a", "b", "c"), result.value)
        assertEquals(3, transport.requests.size)
        val last = JSONObject(String(transport.requests[2].body!!))
        assertFalse(last.has("reasoning")); assertFalse(last.has("text"))
        assertTrue(last.getString("instructions").endsWith("and nothing else."))
    }

    @Test fun unsupportedCapabilityWithoutAFixFailsOnce() = runBlocking {
        val transport = FakeTransport { error(400, "subscription_sharing_unsupported_capability", "input.image") }
        val client = OpenAiPlanClient(transport, "https://example.test/v1")
        try { client.readScreenshots(OpenAiCredential.ApiKey("k", "m"), listOf("AAA")); fail() } catch (e: OpenAiException) {
            assertEquals(OpenAiFailure.PLAN_UNSUPPORTED, e.failure)
            assertEquals("input.image", e.apiParam)
        }
        assertEquals(1, transport.requests.size)
    }

    @Test fun transportErrorsBecomeTimeoutOrConnection() = runBlocking {
        val timeout = OpenAiPlanClient(FakeTransport { throw java.net.SocketTimeoutException() }, "https://example.test/v1")
        try { timeout.rewrite(OpenAiCredential.ApiKey("k", "m"), RewritePrompt("hey", "casual", style())); fail() } catch (e: OpenAiException) { assertEquals(OpenAiFailure.TIMEOUT, e.failure) }
        val down = OpenAiPlanClient(FakeTransport { throw java.io.IOException("dns") }, "https://example.test/v1")
        try { down.rewrite(OpenAiCredential.ApiKey("k", "m"), RewritePrompt("hey", "casual", style())); fail() } catch (e: OpenAiException) { assertEquals(OpenAiFailure.CONNECTION, e.failure) }
    }

    @Test fun noListedModelFails() = runBlocking {
        val client = OpenAiPlanClient(FakeTransport { models("x" to "hidden") }, "https://example.test/v1")
        try { client.resolveModel(OpenAiCredential.ChatGptPlan("tok")); fail() } catch (e: OpenAiException) { assertEquals(OpenAiFailure.NO_MODEL, e.failure) }
    }
}

class ProfileMemoryTest {
    private fun m(text: String) = MemoryTurn(true, text)
    private fun t(text: String) = MemoryTurn(false, text)

    @Test fun rememberAppendsWithoutDuplicatingOverlap() {
        val store = ProfileMemoryStore(TransientProfileMemoryStorage())
        assertTrue(store.remember("p1", listOf(t("hi"), m("hey"), t("how far"))))
        assertFalse(store.remember("p1", listOf(m("hey"), t("how far"))), "already contained")
        assertTrue(store.remember("p1", listOf(t("how far"), m("i dey"), t("cool"))))
        assertEquals(5, store.turnCount("p1"))
        assertEquals(0, store.turnCount("p2"))
        store.forget("p1")
        assertEquals(0, store.turnCount("p1"))
    }

    @Test fun recallExcludesCurrentContextAndBuildsStyle() {
        val store = ProfileMemoryStore(TransientProfileMemoryStorage())
        store.remember("p", listOf(t("hi"), m("hey hey"), m("you good?"), t("plans?"), m("omw now"), t("ok"), m("lol sure")))
        val recall = store.recall("p", listOf(t("plans?"), m("omw now")))
        assertEquals(listOf("hi", "hey hey", "you good?", "ok", "lol sure"), recall.history.map { it.text })
        assertEquals(listOf("hey hey", "you good?", "lol sure"), recall.styleExamples)
        assertNotNull(recall.styleSummary)
        assertTrue("often start lowercase" in recall.styleSummary!!)
        assertTrue("often omit final punctuation" in recall.styleSummary!!)
        assertNull(ProfileMemory.recall(listOf(m("a"), m("b")), emptyList()).styleSummary, "fewer than three outgoing")
    }

    @Test fun boundsAreEnforced() {
        val many = (1..130).map { m("message $it") }
        val merged = ProfileMemory.merge(emptyList(), many)!!
        assertEquals(120, merged.size)
        assertEquals("message 130", merged.last().text)
        val huge = (1..120).map { m("é".repeat(995) + "%05d".format(it)) }
        val bytes = ProfileMemory.encode(huge)
        assertTrue(bytes.size <= ProfileMemory.MAX_BYTES)
        val decoded = ProfileMemory.decode(bytes)!!
        assertTrue(decoded.size < 120 && decoded.last().text.endsWith("00120"), "oldest dropped first")
        assertNull(ProfileMemory.merge(emptyList(), listOf(m("y".repeat(1_001)))), "oversized turns are ignored")
        assertNull(ProfileMemory.decode("[{\"speaker\":\"robot\",\"text\":\"x\"}]".toByteArray()))
        assertNull(ProfileMemory.decode("garbage".toByteArray()))
    }

    @Test fun styleWithRecallMergesLikeTheBackend() {
        val style = PromptStyle("casual", "mixed casing", listOf("a", "b"))
        val merged = ProfileMemory.styleWithRecall(style, ProfileRecall(emptyList(), "often include emoji", listOf("c", "a", "d", "e", "f")))
        assertEquals("mixed casing often include emoji", merged.summary)
        assertEquals(listOf("a", "d", "e", "f", "b"), merged.examples)
    }

    @Test fun overlapHelpers() {
        val saved = listOf(t("a"), m("b"), t("c"))
        assertEquals(2, ProfileMemory.largestOverlap(saved, listOf(m("b"), t("c"), m("d"))))
        assertEquals(0, ProfileMemory.largestOverlap(saved, listOf(m("z"))))
        assertTrue(ProfileMemory.containsSequence(saved, listOf(m("b"), t("c"))))
        assertFalse(ProfileMemory.containsSequence(saved, listOf(t("c"), m("b"))))
        assertFalse(ProfileMemory.containsSequence(saved, listOf(t("a"), m("b"), t("c"), m("d"))))
    }
}
