package io.github.opensabre.iqc.conversation;

import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class TxtConversationParser {

    private static final Pattern MESSAGE_PATTERN = Pattern.compile(
            "^(\\d+)\\(([^)]+)\\):\\[([^]]+)]\\s?(.*)$");
    private static final Pattern ROLE_TIME_PATTERN = Pattern.compile("^([^:：\\[]+)[:：]\\s*\\[([^]]+)]\\s*(.+)$");
    private static final Pattern TIME_ROLE_PATTERN = Pattern.compile("^\\[([^]]+)]\\s*([^:：]+)[:：]\\s*(.+)$");
    private static final Pattern ROLE_TEXT_PATTERN = Pattern.compile("^([^:：]+)[:：]\\s*(.+)$");
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");

    public ConversationParseResult parse(String content) {
        List<ConversationMessageDraft> messages = new ArrayList<>();
        List<ConversationParseResult.ConversationParseError> errors = new ArrayList<>();
        int ignoredBlankLines = 0;
        Integer previousSequence = null;

        String normalized = content == null ? "" : content.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\\n", -1);
        for (int index = 0; index < lines.length; index++) {
            int lineNumber = index + 1;
            String rawLine = lines[index];
            if (rawLine.isBlank()) {
                ignoredBlankLines++;
                continue;
            }

            Matcher matcher = MESSAGE_PATTERN.matcher(rawLine.trim());
            String speakerRole;
            String timeValue;
            String message;
            int sequence;
            if (matcher.matches()) {
                speakerRole = matcher.group(2).trim(); timeValue = matcher.group(3).trim(); message = matcher.group(4);
                try { sequence = Integer.parseInt(matcher.group(1)); }
                catch (NumberFormatException exception) {
                    errors.add(new ConversationParseResult.ConversationParseError(lineNumber, rawLine, "消息序号超出可支持范围"));
                    continue;
                }
            } else {
                Matcher roleTime = ROLE_TIME_PATTERN.matcher(rawLine.trim());
                Matcher timeRole = TIME_ROLE_PATTERN.matcher(rawLine.trim());
                Matcher roleText = ROLE_TEXT_PATTERN.matcher(rawLine.trim());
                sequence = previousSequence == null ? 0 : previousSequence + 1;
                if (roleTime.matches()) {
                    speakerRole = roleTime.group(1).trim(); timeValue = roleTime.group(2).trim(); message = roleTime.group(3);
                } else if (timeRole.matches()) {
                    timeValue = timeRole.group(1).trim(); speakerRole = timeRole.group(2).trim(); message = timeRole.group(3);
                } else if (roleText.matches()) {
                    speakerRole = roleText.group(1).trim(); message = roleText.group(2);
                    timeValue = LocalTime.MIDNIGHT.plusSeconds(sequence).format(TIME_FORMATTER);
                } else {
                    errors.add(new ConversationParseResult.ConversationParseError(lineNumber, rawLine,
                            "无法识别；支持“角色：正文”“角色：[HH:mm:ss] 正文”或标准序号格式"));
                    continue;
                }
            }

            if (speakerRole.isEmpty()) {
                errors.add(new ConversationParseResult.ConversationParseError(lineNumber, rawLine, "说话人角色不能为空"));
                continue;
            }
            if (message.isBlank()) {
                errors.add(new ConversationParseResult.ConversationParseError(lineNumber, rawLine, "消息正文不能为空"));
                continue;
            }
            if (previousSequence != null && sequence <= previousSequence) {
                errors.add(new ConversationParseResult.ConversationParseError(lineNumber, rawLine, "消息序号必须严格递增"));
                continue;
            }

            LocalTime relativeTime;
            try {
                relativeTime = LocalTime.parse(timeValue, TIME_FORMATTER);
            } catch (DateTimeParseException exception) {
                errors.add(new ConversationParseResult.ConversationParseError(lineNumber, rawLine, "相对时间必须是 HH:mm:ss"));
                continue;
            }

            messages.add(new ConversationMessageDraft(sequence, speakerRole, relativeTime, message, rawLine, lineNumber));
            previousSequence = sequence;
        }
        return new ConversationParseResult(List.copyOf(messages), List.copyOf(errors), ignoredBlankLines);
    }
}
