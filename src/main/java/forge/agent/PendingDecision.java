package forge.agent;

import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * A blocking prompt from the host, parked until the bridge answers it. Options are shown to the
 * agent as strings; the answer is a list of option indices.
 */
final class PendingDecision {
    final int id;
    final String kind;
    final String prompt;
    final List<String> options;
    final int min;
    final int max;
    final String defaultAnswer;
    /** For kind "assign": the total to split among the options. Null for every other kind. */
    final Integer amount;
    private final CountDownLatch latch = new CountDownLatch(1);
    private volatile List<Integer> indices;
    private volatile String text;

    PendingDecision(final int id, final String kind, final String prompt, final List<String> options,
            final int min, final int max, final String defaultAnswer) {
        this(id, kind, prompt, options, min, max, defaultAnswer, null);
    }

    PendingDecision(final int id, final String kind, final String prompt, final List<String> options,
            final int min, final int max, final String defaultAnswer, final Integer amount) {
        this.id = id;
        this.kind = kind;
        this.prompt = prompt;
        this.options = options;
        this.min = min;
        this.max = max;
        this.defaultAnswer = defaultAnswer;
        this.amount = amount;
    }

    void answer(final List<Integer> indices, final String text) {
        this.indices = indices;
        this.text = text;
        latch.countDown();
    }

    void await() throws InterruptedException {
        latch.await();
    }

    List<Integer> indices() {
        return indices;
    }

    String text() {
        return text;
    }

    boolean isAnswered() {
        return latch.getCount() == 0;
    }
}
