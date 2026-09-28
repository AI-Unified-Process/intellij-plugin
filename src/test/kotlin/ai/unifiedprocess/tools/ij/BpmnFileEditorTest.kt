package ai.unifiedprocess.tools.ij

import org.junit.Assert.assertEquals
import org.junit.Test

class BpmnFileEditorTest {

    @Test
    fun `jsString escapes quotes, backslashes and line breaks`() {
        assertEquals("\"a \\\"b\\\" c\\\\d\\ne\\r\\tf\"", jsString("a \"b\" c\\d\ne\r\tf"))
    }

    @Test
    fun `jsString keeps the XML from closing the script element`() {
        assertEquals("\"\\u003c/script>\\u003cbpmn:definitions/>\"", jsString("</script><bpmn:definitions/>"))
    }

    @Test
    fun `jsString escapes the line separators JavaScript does not allow in literals`() {
        assertEquals("\"\\u2028\\u2029\\u0001\"", jsString("\u2028\u2029\u0001"))
    }
}
