package forge.agent;

import static org.testng.Assert.assertTrue;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;

import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import forge.gamemodes.net.CompatibleObjectDecoder;
import forge.gamemodes.net.CompatibleObjectEncoder;
import forge.gamemodes.net.event.HeartbeatEvent;
import forge.gamemodes.net.event.MessageEvent;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.serialization.ClassResolvers;

/**
 * The seat stays connected while it keeps writing to a host that sends nothing. Forge's client sends a
 * heartbeat after 15 s without writing and closes the connection after 45 s without reading, and a
 * Forge host answers a heartbeat with a heartbeat. The host here speaks Forge's wire format, answers
 * heartbeats like a Forge host and is otherwise quiet. The seat sends a chat line every 7 s for 63 s.
 *
 * Without the bridge's own heartbeat the client never went 15 s without writing, sent no heartbeat,
 * and closed the connection at 45 s.
 */
public class AgentHeartbeatTest {

    private static final long WRITE_EVERY_MILLIS = 7000;
    private static final int WRITES = 9;

    private final AtomicInteger heartbeats = new AtomicInteger();
    private final AtomicInteger chatLines = new AtomicInteger();
    private EventLoopGroup group;
    private AgentBridge bridge;

    @BeforeClass
    public void initializeModel() {
        RiggedMatch.initializeModel();
    }

    @AfterClass(alwaysRun = true)
    public void tearDown() {
        if (bridge != null) {
            bridge.stop();
        }
        if (group != null) {
            group.shutdownGracefully();
        }
    }

    @Test(timeOut = 120_000)
    public void aSeatThatKeepsWritingToAQuietHostStaysConnected() throws Exception {
        group = new NioEventLoopGroup(1);
        final Channel host = new ServerBootstrap().group(group).channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(final SocketChannel ch) {
                        ch.pipeline().addLast(
                                new CompatibleObjectEncoder(null),
                                new CompatibleObjectDecoder(9766 * 1024, ClassResolvers.cacheDisabled(null)),
                                new QuietHost());
                    }
                }).bind("127.0.0.1", 0).sync().channel();
        final int hostPort = ((InetSocketAddress) host.localAddress()).getPort();

        bridge = new AgentBridge();
        bridge.start(0);
        final BridgeHttp http = new BridgeHttp(bridge.port());
        http.post("/connect", BridgeHttp.body("host", "127.0.0.1", "port", hostPort, "name", "AgentBot"));

        final long t0 = System.currentTimeMillis();
        for (int i = 1; i <= WRITES; i++) {
            Thread.sleep(WRITE_EVERY_MILLIS);
            http.post("/chat", BridgeHttp.body("text", "line " + i));
            final long seconds = (System.currentTimeMillis() - t0) / 1000;
            assertTrue(http.get("/status").getAsJsonObject("connection").get("connected").getAsBoolean(),
                    "connected after " + seconds + " s; the host has seen " + chatLines.get() + " chat lines and "
                            + heartbeats.get() + " heartbeats");
        }
        AgentGameTest.waitFor("the host reads the last chat line", 5000, () -> chatLines.get() == WRITES);
        assertTrue(heartbeats.get() > 0, "the seat sent heartbeats");
    }

    /** Answers a heartbeat like a Forge host and sends nothing else. */
    private final class QuietHost extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(final ChannelHandlerContext ctx, final Object msg) {
            if (msg instanceof HeartbeatEvent) {
                heartbeats.incrementAndGet();
                ctx.writeAndFlush(new HeartbeatEvent());
            } else if (msg instanceof MessageEvent) {
                chatLines.incrementAndGet();
            }
        }
    }
}
