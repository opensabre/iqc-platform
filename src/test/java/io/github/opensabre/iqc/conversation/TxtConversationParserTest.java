package io.github.opensabre.iqc.conversation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TxtConversationParserTest {

    private final TxtConversationParser parser = new TxtConversationParser();

    @Test
    void parsesTheProductSampleConversation() {
        ConversationParseResult result = parser.parse("""
                0(agent):[00:00:01]您好，我是宁波银行催收专员
                1(user):[00:00:03]你们现在有什么优惠吗
                2(agent):[00:00:06]可以申请减免，今天处理更方便
                3(user):[00:00:12]那我再看看
                4(agent):[00:00:16]请您今天完成还款
                """);

        assertTrue(result.successful());
        assertEquals(5, result.messages().size());
        assertEquals("agent", result.messages().getFirst().speakerRole());
        assertEquals("请您今天完成还款", result.messages().getLast().content());
    }

    @Test
    void returnsLineErrorsWithoutDroppingValidMessages() {
        ConversationParseResult result = parser.parse("""
                0(agent):[00:00:01]你好
                invalid line
                2(user):[00:70:00]无效时间
                3(user):[00:00:03]有效消息
                """);

        assertEquals(2, result.messages().size());
        assertEquals(2, result.errors().size());
        assertEquals(2, result.errors().getFirst().lineNumber());
    }

    @Test
    void recordsOverflowingSequenceAsLineErrorAndContinues() {
        ConversationParseResult result = parser.parse(
                "999999999999999999999(agent):[00:00:01]异常\n1(user):[00:00:02]正常");

        assertEquals(1, result.messages().size());
        assertEquals("正常", result.messages().getFirst().content());
        assertEquals(1, result.errors().size());
        assertEquals(1, result.errors().getFirst().lineNumber());
        assertTrue(result.errors().getFirst().reason().contains("超出"));
    }

    @Test
    void acceptsCommonChineseTranscriptFormatsAndGeneratesMissingSequenceAndTime() {
        ConversationParseResult result = parser.parse("""
                客服：您好，请问有什么可以帮您
                客户：[10:02:03] 我想查询账单
                [10:02:08] 客服：好的，请稍等
                """);

        assertTrue(result.successful());
        assertEquals(3, result.messages().size());
        assertEquals(0, result.messages().getFirst().sequence());
        assertEquals("agent", result.messages().getFirst().speakerRole());
        assertEquals("user", result.messages().get(1).speakerRole());
        assertEquals("agent", result.messages().getLast().speakerRole());
        assertEquals("客户：[10:02:03] 我想查询账单", result.messages().get(1).rawLine());
        assertEquals("10:02:08", result.messages().getLast().relativeTime().toString());
    }

    @Test
    void canonicalizesKnownEnglishAliasesButDoesNotAssignUnknownSpeakers() {
        ConversationParseResult result = parser.parse("""
                0(Customer):[00:00:01]我要办理
                1(AGENT):[00:00:02]好的
                2(系统):[00:00:03]会话结束
                """);

        assertTrue(result.successful());
        assertEquals("customer", result.messages().get(0).speakerRole());
        assertEquals("agent", result.messages().get(1).speakerRole());
        assertEquals("系统", result.messages().get(2).speakerRole());
    }
}
