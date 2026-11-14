package com.aharou.feature.plugin

import org.junit.runner.Description
import org.junit.runner.JUnitCore
import org.junit.runner.notification.Failure
import org.junit.runner.notification.RunListener
import java.io.File
import java.time.Instant
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

object PluginPatrolRunner {
    private val suites = listOf(PluginPatrolTest::class.java, HostPluginBridgeTest::class.java,
        HostPluginRuntimeTest::class.java, HostPluginHttpInterceptorTest::class.java)
    private class Case(val description: Description, var failure: Failure? = null, var skipped: Boolean = false)

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size in 1..2) { "Expected results directory and optional exact method filter" }
        val directory = File(args[0]).apply { mkdirs() }
        var unsuccessful = false
        for (suite in suites) {
            val cases = linkedMapOf<Description, Case>()
            val runner = JUnitCore()
            runner.addListener(object : RunListener() {
                override fun testStarted(description: Description) { cases[description] = Case(description) }
                override fun testFailure(failure: Failure) {
                    cases.getOrPut(failure.description) { Case(failure.description) }.failure = failure
                }
                override fun testIgnored(description: Description) {
                    unsuccessful = true
                    cases[description] = Case(description, skipped = true)
                }
                override fun testAssumptionFailure(failure: Failure) {
                    unsuccessful = true
                    cases.getOrPut(failure.description) { Case(failure.description) }.skipped = true
                }
            })
            val request = org.junit.runner.Request.aClass(suite).let { original ->
                if (args.size == 1) original else original.filterWith(object : org.junit.runner.manipulation.Filter() {
                    override fun shouldRun(description: Description): Boolean =
                        description.methodName == args[1] || description.children.any { shouldRun(it) }
                    override fun describe(): String = "Exact patrol regression filter"
                })
            }
            val result = runner.run(request)
            val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument()
            val root = document.createElement("testsuite").apply {
                setAttribute("name", suite.name)
                setAttribute("tests", cases.size.toString())
                setAttribute("failures", cases.values.count { it.failure != null }.toString())
                setAttribute("errors", "0")
                setAttribute("skipped", cases.values.count { it.skipped }.toString())
                setAttribute("timestamp", Instant.now().toString())
            }
            document.appendChild(root)
            for (case in cases.values) {
                val node = document.createElement("testcase").apply {
                    setAttribute("name", case.description.methodName ?: "initializationError")
                    setAttribute("classname", suite.name)
                }
                case.failure?.let { failure ->
                    node.appendChild(document.createElement("failure").apply {
                        setAttribute("message", failure.message.orEmpty())
                        textContent = failure.trace
                    })
                }
                if (case.skipped) node.appendChild(document.createElement("skipped"))
                root.appendChild(node)
            }
            TransformerFactory.newInstance().newTransformer().transform(DOMSource(document),
                StreamResult(File(directory, "TEST-${suite.name}.xml")))
            println("PLUGIN_PATROL_SUITE ${suite.name} cases=${cases.size} failures=${result.failureCount} skipped=${result.ignoreCount}")
            unsuccessful = unsuccessful || !result.wasSuccessful() || cases.isEmpty() || cases.values.any { it.skipped }
        }
        check(!unsuccessful) { "Plugin patrol has failed, missing or skipped tests" }
    }
}
