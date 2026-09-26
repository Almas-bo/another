package city.subroutine.sandbox.protocol;

import city.subroutine.sandbox.api.EntryPoint;
import city.subroutine.sandbox.api.ErrorReport;
import city.subroutine.sandbox.api.ExecutionRequest;
import city.subroutine.sandbox.api.SandboxLimits;
import city.subroutine.sandbox.api.StackFrameInfo;
import city.subroutine.sandbox.api.TestMetrics;
import city.subroutine.sandbox.api.TestOutcome;
import city.subroutine.sandbox.api.TestStatus;
import city.subroutine.sandbox.api.ThreadSnapshot;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WireCodecTest {

    @Test
    void requestRoundTrip() throws IOException {
        ExecutionRequest request = ExecutionRequest.singleSource("req-1", "package city.player; /* Привет */",
                new EntryPoint.MethodEntry("city.player.PowerGrid", "totalLoad", List.of("int[]"), "long"),
                "city.subroutine.levels.powergrid.PowerGridSuite", "city.player", List.of("city.api"),
                SandboxLimits.defaults());
        Frame frame = FrameIO.frame(FrameType.REQUEST, o -> WireCodec.writeRequest(o, request));
        assertEquals(request, FrameIO.decode(roundTrip(frame), WireCodec::readRequest));
    }

    @Test
    void outcomeRoundTripWithNestedErrors() throws IOException {
        StackFrameInfo frame = new StackFrameInfo("city.player.A", "run", "A.java", 12, true);
        ErrorReport cause = new ErrorReport("java.io.IOException", null, List.of(frame), 0, Optional.empty(), List.of());
        ErrorReport error = new ErrorReport("java.lang.IllegalStateException", "сбой", List.of(frame), 3,
                Optional.of(cause), List.of(cause));
        TestOutcome outcome = new TestOutcome("t-1", "Тест", TestStatus.DEADLOCK, "msg", "1", "2", Optional.of(error),
                new TestMetrics(1, 2, 3, 4, 5, 6), "вывод", true,
                List.of(new ThreadSnapshot("w-1", "BLOCKED", true, "lock@1", "w-2", List.of(frame))),
                List.of("pool-1-thread-1"));
        Frame encoded = FrameIO.frame(FrameType.TEST_RESULT, o -> WireCodec.writeOutcome(o, outcome));
        assertEquals(outcome, FrameIO.decode(roundTrip(encoded), WireCodec::readOutcome));
    }

    @Test
    void corruptedFrameIsRejected() {
        Frame garbage = new Frame(FrameType.TEST_RESULT, new byte[] {0, 0, 0, 5, 'a'});
        assertThrows(ProtocolException.class, () -> FrameIO.decode(garbage, WireCodec::readOutcome));
    }

    @Test
    void oversizedLengthIsRejectedWithoutAllocation() {
        byte[] header = {(byte) FrameType.TEST_RESULT.code(), 0x7f, (byte) 0xff, (byte) 0xff, (byte) 0xff};
        assertThrows(ProtocolException.class,
                () -> FrameIO.read(new DataInputStream(new ByteArrayInputStream(header))));
    }

    private static Frame roundTrip(Frame frame) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        FrameIO.write(new DataOutputStream(bytes), frame);
        return FrameIO.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));
    }
}
