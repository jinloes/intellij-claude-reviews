package com.jinloes.prpilot.sidecar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class StdioFrameCodecTest {
    private final StdioFrameCodec codec = new StdioFrameCodec();

    @Test
    void readsConsecutiveUtf8FramesByByteLength() throws IOException {
        byte[] first = "{\"message\":\"héllo\"}".getBytes(StandardCharsets.UTF_8);
        byte[] second = "{\"message\":\"world\"}".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        codec.writeFrame(encoded, first);
        codec.writeFrame(encoded, second);

        ByteArrayInputStream input = new ByteArrayInputStream(encoded.toByteArray());

        assertThat(codec.readFrame(input)).isEqualTo(first);
        assertThat(codec.readFrame(input)).isEqualTo(second);
        assertThat(codec.readFrame(input)).isNull();
    }

    @Test
    void rejectsMissingContentLength() {
        ByteArrayInputStream input =
                new ByteArrayInputStream(
                        "Content-Type: application/json\r\n\r\n{}"
                                .getBytes(StandardCharsets.US_ASCII));

        assertThatThrownBy(() -> codec.readFrame(input))
                .isInstanceOf(IOException.class)
                .hasMessage("JSON-RPC header is missing Content-Length");
    }

    @Test
    void rejectsPayloadsLargerThanTheProtocolLimit() {
        ByteArrayInputStream input =
                new ByteArrayInputStream(
                        ("Content-Length: " + (StdioFrameCodec.MAX_MESSAGE_BYTES + 1) + "\r\n\r\n")
                                .getBytes(StandardCharsets.US_ASCII));

        assertThatThrownBy(() -> codec.readFrame(input))
                .isInstanceOf(IOException.class)
                .hasMessage("JSON-RPC Content-Length is outside the allowed range");
    }

    @Test
    void rejectsTruncatedPayloads() {
        ByteArrayInputStream input =
                new ByteArrayInputStream(
                        "Content-Length: 5\r\n\r\n{}".getBytes(StandardCharsets.US_ASCII));

        assertThatThrownBy(() -> codec.readFrame(input))
                .isInstanceOf(IOException.class)
                .hasMessage("Unexpected end of input while reading JSON-RPC payload");
    }
}
