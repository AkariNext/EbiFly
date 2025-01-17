package jp.jyn.ebifly.fly;

import jp.jyn.ebifly.EbiFly;
import jp.jyn.ebifly.config.MainConfig;
import jp.jyn.ebifly.config.MessageConfig;
import jp.jyn.jbukkitlib.config.locale.BukkitLocale;
import jp.jyn.jbukkitlib.config.parser.component.ComponentVariable;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.boss.KeyedBossBar;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongFunction;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public class FlyRepository implements EbiFly {
    public final Map<UUID, FlightStatus> flying = new ConcurrentHashMap<>();

    private final BukkitLocale<MessageConfig> message;
    private final ScheduledExecutorService executor;
    private final VaultEconomy economy;
    private final Consumer<Runnable> syncCall;
    private final Consumer<Player> removeHandler;

    private final int notify;
    private final double price;
    private final Boolean refundType;
    private final Consumer<Player> noticeDisable;
    private final Consumer<Player> noticeTimeout;

    boolean showBossbar;
    Pattern pattern;

    public FlyRepository(MainConfig config, BukkitLocale<MessageConfig> message,
                         ScheduledExecutorService executor, VaultEconomy economy,
                         Consumer<Runnable> syncCall, Consumer<Player> removeHandler, boolean showBossbar, String disabledWorldPattern) {
        this.message = message;
        this.executor = executor;
        this.economy = economy;
        this.syncCall = syncCall;
        this.removeHandler = removeHandler;

        this.notify = config.noticeTimeoutSecond;
        if (economy == null) {
            this.price = 0;
            this.refundType = null;
        } else {
            this.price = config.economy.price;
            this.refundType = config.economy.refund;
        }

        this.noticeDisable = config.noticeDisable.merge();
        this.noticeTimeout = config.noticeTimeout.merge();

        this.showBossbar = showBossbar;

        try {
            this.pattern = Pattern.compile(disabledWorldPattern);
        } catch (PatternSyntaxException ex) {
            throw new IllegalArgumentException("フライ禁止ワールドの正規表現が間違っています。", ex);
        }
    }

    private boolean isNotifyEnabled() {
        return notify > 0;
    }

    private FlightStatus remove(Player player, boolean notice) {
        var r = flying.remove(player.getUniqueId());
        if (r == null) {
            return null;
        }
        r.cancelTimer();

        r.bb.setVisible(false);

        // 引数を使わない事でラムダのインスタンスが無駄に作られないように
        final Consumer<Player> f = p -> {
            switch (p.getGameMode()) {
                case SURVIVAL, ADVENTURE -> p.setAllowFlight(false);
            }
        };
        final Consumer<Player> m = notice ? p -> { // IFスリカエ
            message.get(p).flyDisable.apply().send(p);
            noticeDisable.accept(p);
        } : p -> {};
        // 無駄な最適化かも

        // スレッド切り替え
        if (Bukkit.isPrimaryThread()) {
            // Player渡して呼ぶだけで済む、ペナルティはほぼゼロのはず
            f.accept(player);
            m.accept(player);
        } else {
            syncCall.accept(() -> {
                f.accept(player);
                m.accept(player);
            });
        }

        removeHandler.accept(player);
        return r;
    }

    public int getTimeLeft(FlightStatus fs) {
        int res = 0;

        var cs = fs.credit;
        for (Credit c : cs) {
            var i = c.minute.get();
            if (i > 0) {
                res += i;
            }
        }

        return res;
    }

    public int getTimeSum(FlightStatus fs) {
        int res = 0;

        var cs = fs.credit;
        for (Credit c : cs) {
            var i = c.boughtMinute;
            res += i;
        }
        return res;
    }

    private void persistTimer(Player player) {
        var v = flying.get(player.getUniqueId());
        if (v == null) {
            // 飛行してないのにタイマーだけ動いてる、スレッド競合？
            throw new IllegalStateException("Player doesn't flying, Thread conflict?"); // 絶妙なタイミングで飛行停止すると正常でも出るかも
        }

        // 1分の周期タイマーのはずなので、1分進めて1クレジット使う
        v.lastConsume.addAndGet(TimeUnit.MINUTES.toNanos(1));
        var cs = v.credit;
        boolean unpaid = true; // 踏み倒し防止(0で呼び出すと支払わずに踏み倒せる->踏み倒されそうになると倍徴収する)
        Credit credit;
        while ((credit = cs.pollFirst()) != null) {
            var i = credit.minute().decrementAndGet();
            if (i > 0) { // クレジット残ありなら返却して今周の消費は終わり
                cs.addFirst(credit);
                updateBossbar(v);
                return;
            } else if (i == 0) {
                // 取り出したクレジットが空になったら次の支払いへ
                unpaid = false; // 支払われたので倍額徴収は不要
                break;
            }
            // 0を下回る == スレッド間競合で既に0だったのを更に減らしたっぽい
            // 捨ててやり直しが必要
        }

        // クレジットは空になった、まだあるかな？
        while ((credit = cs.pollFirst()) != null) {
            if (credit.minute().get() > 0) {
                // まだあるみたいなので返却して終わり
                cs.addFirst(credit);
                updateBossbar(v);
                return;
            }
        }

        // 支払いが無効になってるなら何もせずにaddできる
        if (economy == null) {
            cs.addLast(new Credit(0, 1, null));
            updateBossbar(v);
            return;
        }

        // ↓がスレッド対応してなさそうなのでメインスレッドに切り替える
        // Vault -> Vaultから先のプラグインがスレッドサポートしてるか分からない
        // パーティクル表示 -> net.minecraft.server.level.WorldServer#sendParticlesがArrayListをイテレーションしてる
        // hasPermission -> org.bukkit.permissions.PermissibleBaseでHashMapを使っている

        // 先にスレッド側で色々作っておく
        var b = unpaid;
        var m = message.get(player).payment;
        syncCall.accept(() -> {
            // 支払い不要
            if (player.hasPermission("fly.free")) {
                cs.addLast(new Credit(0, 1, null));
                updateBossbar(v);
                return;
            }

            // 踏み倒しはさせないぞ
            if (b) {
                economy.withdraw(player, price); // 踏み倒されても1分が精一杯なので要らない処理のような気はする
            }

            // お金ないならｵﾁﾛｰ!
            m.insufficient.accept(player, ComponentVariable.init().put("price", () -> economy.format(price)));
            remove(player, true);
        });
    }

    private void updateBossbar(FlightStatus fs) {
        if (!showBossbar)
            return;

        int left = getTimeLeft(fs);
        int sum = getTimeSum(fs);


        fs.bb.setTitle("Fly残り時間: "+left+"分");
        fs.bb.setProgress(1.0D-(((double) left) / ((double)sum)));
        if (left > 0) {
            fs.bb.setVisible(true);
        } else {
            fs.bb.setVisible(false);
        }
    }

    private void stopTimer(Player player, boolean notify) {
        var v = flying.get(player.getUniqueId());
        if (v == null) {
            throw new IllegalStateException("Player doesn't flying, Thread conflict?");
        }
        // 後の簡略化のためにクレジットを消費させる
        v.useCredit();

        // クレジット残数チェック
        int c = v.countAllCredits();
        if (c > 1) { // 後からクレジットが追加されてる -> 警告タイマーの入れ直し
            long delay = TimeUnit.MINUTES.toNanos(c); // 総飛行時間
            delay -= v.getElapsed(); // 既に飛行してる分を引く
            delay -= TimeUnit.SECONDS.toNanos(this.notify);
            v.setTimer(executor.schedule(() -> stopTimer(player, isNotifyEnabled()), delay, TimeUnit.NANOSECONDS));
        } else if (notify) { // 停止タイマー入れ直す
            // パーティクル表示があるのでメインスレッドで
            var m = message.get(player).flyTimeout;
            syncCall.accept(() -> {
                noticeTimeout.accept(player);
                m.accept(player, () -> String.valueOf(this.notify));
            });
            v.setTimer(executor.schedule(() -> stopTimer(player, false), this.notify, TimeUnit.SECONDS));
        } else { // 停止
            // ズレが大きければコッソリ待つ的な処理があった方が良いかも？ ScheduledExecutorServiceの精度次第
            remove(player, true);
        }

        updateBossbar(v);
    }

    @Override
    public boolean isFlying(Player player) {
        return flying.containsKey(player.getUniqueId());
    }

    @Override
    public boolean isPersist(Player player) {
        var v = flying.get(player.getUniqueId());
        return v != null && v.persist;
    }

    @Override
    public boolean persist(Player player, boolean enable) {
        var v = flying.get(player.getUniqueId());
        if (v == null || v.persist == enable) {
            // 飛んでない || 既に指定されたモード
            return false;
        }

        // タイマー入れ替え
        if (enable) {
            // 使ったクレジット減らしておく(persistTimerは1分ごとに徴収するので、既に使われている分を徴収せずにタイマーを切り替えると未徴収分だけ飛行時間が伸びる)
            final long MINUTE_NANOS = TimeUnit.MINUTES.toNanos(1);
            var delay = Math.max(MINUTE_NANOS - v.useCredit(), 0); // 次にクレジットを減らすタイミング(分区切りのタイミングになる時間)
            v.setTimer(executor.scheduleAtFixedRate(() -> persistTimer(player),
                delay, MINUTE_NANOS, TimeUnit.NANOSECONDS));
            // 30秒経過後に切り替え -> 30秒後に支払い、1分周期
            // 1分30秒経過後に切り替え -> 1クレジット(1分)を消費して30秒後に支払い、1分周期
        } else {
            v.reSchedule(delay -> executor.schedule(() -> stopTimer(player, isNotifyEnabled()),
                delay - TimeUnit.SECONDS.toNanos(notify), TimeUnit.NANOSECONDS));
        }
        v.persist = enable;
        return true;
    }

    @Override
    public boolean addCredit(Player player, double price, int minute, OfflinePlayer payer, boolean persist) {
        FlightStatus v = flying.computeIfAbsent(player.getUniqueId(), u -> new FlightStatus(player));
        v.credit.addLast(new Credit(price, minute, payer));
        if (!v.isTemporaryStop())
            if (Bukkit.isPrimaryThread()) {
                player.setAllowFlight(true);
            } else {
                syncCall.accept(() -> player.setAllowFlight(true));
            }

        updateBossbar(v);

        if (v.isTimerInitialized()) {
            // タイマーが動いてる -> 非persistの時だけタイマー入れ直す (persistはどのみち周期タイマーで消えていくので)
            if (!v.persist) {
                v.reSchedule(delay -> executor.schedule(() -> stopTimer(player, isNotifyEnabled()),
                    delay - TimeUnit.SECONDS.toNanos(notify), TimeUnit.NANOSECONDS));
            }
            return false;
        } else {
            // タイマーが動いてない -> 必要なタイマーを入れる
            if (persist) {
                v.setTimer(executor.scheduleAtFixedRate(() -> persistTimer(player), 1, 1, TimeUnit.MINUTES));
            } else {
                v.setTimer(executor.schedule(() -> stopTimer(player, isNotifyEnabled()),
                    TimeUnit.MINUTES.toSeconds(minute) - this.notify, TimeUnit.SECONDS));
            }
            return true;
        }


    }

    public void stopFlyTemporary(Player player) {
        FlightStatus v = flying.get(player.getUniqueId());
        if (v == null) {
            return;
        }
        if (v.temporaryStop) {
            return;
        }

        v.temporaryStop = true;
        player.setAllowFlight(false);

        player.sendMessage("§c一時的にフライを無効化しました。");
    }

    public void resumeFlyTemporary(Player player) {
        FlightStatus v = flying.get(player.getUniqueId());
        if (v == null) {
            return;
        }
        if (!v.temporaryStop) {
            return;
        }

        v.bb.addPlayer(player);

        v.temporaryStop = false;
        player.setAllowFlight(true);

        player.sendMessage("§cフライを自動的に再開しました。");
    }

    // 飛んでなければ-1、十分なら0、不足していたら足りない数
    @Override
    public int useCredit(Player player, int minute, boolean notice) {
        var v = flying.get(player.getUniqueId());
        if (v == null) {
            return -1;
        }

        int i = v.useCredit(minute);
        if (i == 0) {
            v.lastConsume.addAndGet(TimeUnit.MINUTES.toNanos(minute));

            updateBossbar(v);
            return 0;
        } else if (i < 0) {
            throw new IllegalStateException("useCredit(int) return less 0");
        }

        // 足りない分戻す
        v.lastConsume.addAndGet(-TimeUnit.MINUTES.toNanos(i));
        // もう飛べないので落とす
        remove(player, notice);

        updateBossbar(v);
        return i;
    }

    @Override
    public Map<OfflinePlayer, Double> stop(Player player) {
        var v = remove(player, false);
        return v == null ? Collections.emptyMap() : v.clearCredits();
    }

    public boolean stopRefund(Player player, boolean notice) {
        var status = remove(player, notice);
        if (status == null) {
            return false;
        } else if (economy == null || refundType == null) {
            return true;
        }

        var refund = status.clearCredits();
        if (refund.isEmpty()) {
            return true;
        }

        if (refundType) {
            refund = Map.of(player, refund.values().stream().mapToDouble(Double::doubleValue).sum());
        }

        var v = ComponentVariable.init();
        for (var entry : refund.entrySet()) {
            economy.deposit(entry.getKey(), entry.getValue());
            var p = entry.getKey().getPlayer();
            if (p != null) {
                v.put("player", p::getName);
                v.put("price", () -> economy.format(entry.getValue()));

                var l = message.get(p).payment;
                if (!p.equals(player)) {
                    l.refundOther.accept(p, v);
                } else if (notice) {
                    l.refund.accept(p, v);
                }
            }
        }
        return true;
    }

    public void stopRefund(Player player) {
        stopRefund(player, true);
    }

    public boolean isFlyDisabledWorld(String worldName) {
        return pattern.matcher(worldName).matches();
    }

    public static final class FlightStatus {
        private final AtomicLong lastConsume;
        private final Deque<Credit> credit;

        private final Object lock = new Object();
        private Future<?> timer = null;
        private volatile boolean persist = false;

        private BossBar bb;
        private boolean temporaryStop = false;

        private FlightStatus(Player pl, AtomicLong lastConsume, Deque<Credit> credit) {
            this.lastConsume = lastConsume;
            this.credit = credit;

            this.bb = Bukkit.createBossBar("Fly残り時間: 0分", BarColor.GREEN, BarStyle.SOLID);
            this.bb.setProgress(0);
            this.bb.addPlayer(pl);
            this.bb.setVisible(false);

        }

        private FlightStatus(Player pl) {
            this(pl, new AtomicLong(System.nanoTime()), new ConcurrentLinkedDeque<>());
        }

        private long getElapsed() {
            return System.nanoTime() - lastConsume.get();
        }

        private void cancelTimer() {
            synchronized (lock) {
                if (timer != null) {
                    timer.cancel(false);
                }
            }
        }

        private void setTimer(Future<?> timer) {
            synchronized (lock) {
                if (this.timer != null) {
                    this.timer.cancel(false);
                }
                this.timer = timer;
            }
        }

        private boolean isTimerInitialized() {
            synchronized (lock) {
                return timer != null;
            }
        }

        public boolean isTemporaryStop() {
            return temporaryStop;
        }

        public void setTemporaryStop(boolean temporaryStop) {
            this.temporaryStop = temporaryStop;
        }

        private void reSchedule(LongFunction<Future<?>> scheduler) {
            long last, delay;
            do {
                last = lastConsume.get();
                // クレジット全数での飛行時間を取得
                delay = TimeUnit.MINUTES.toMillis(countAllCredits());
                // 経過時間分引く
                delay -= System.nanoTime() - last;
            } while (last != lastConsume.get()); // Compare And Loop
            // ScheduledFuture#getDelayを使う方法だとnotifyの時間分ずつズレるのでこうする
            setTimer(scheduler.apply(delay));
        }

        private int countAllCredits() {
            return credit.stream().mapToInt(m -> Math.max(m.minute().get(), 0)).sum();
        }

        private long useCredit() {
            while (true) {
                long old = lastConsume.get();
                long elapsed = System.nanoTime() - old;
                long use = TimeUnit.NANOSECONDS.toMinutes(elapsed); // 既に消費されてるべきクレジット数
                long n = TimeUnit.MINUTES.toNanos(use); // 消費された時間
                if (lastConsume.compareAndSet(old, old + n)) { // 消費した分だけ進めておく
                    var i = useCredit((int) use);
                    if (i > 0) {
                        // 未回収分があれば戻す
                        lastConsume.addAndGet(-TimeUnit.MINUTES.toNanos(i));
                    }
                    return elapsed - n; // 経過時間 - 消費分 = 未徴収分
                    // 未回収分があると計算が狂うが、ここで欲しいのは端数なので気にしない (elapsed % TimeUnit.MINUTES.toNanos(1)を減算で書き換えてるだけ)
                }
            }
        }

        private int useCredit(int quantity) {
            Credit c;
            OUTER:
            while (quantity > 0 && (c = credit.pollFirst()) != null) {
                int old, use, remain;
                do {
                    old = c.minute().get();
                    if (old <= 0) { // 空クレジットが入ってたら捨てて次へ
                        continue OUTER;
                    }
                    use = Math.min(old, quantity);
                    remain = old - use;
                } while (!c.minute().compareAndSet(old, remain));

                // 消費して戻す
                quantity -= use;
                if (remain > 0) {
                    credit.addFirst(c);
                }
            }
            return quantity;
        }

        private Map<OfflinePlayer, Double> clearCredits() {
            // クレジット消費
            long nano = useCredit(); // 未徴収分
            if (credit.isEmpty()) {
                return Collections.emptyMap();
            }

            // 秒割料金
            Credit c;
            int remain;
            do {
                if ((c = credit.pollFirst()) == null) {
                    return Collections.emptyMap(); // クレジットないならこの先の処理は全て無駄なのでreturn
                }
            } while (c.payer() == null || (remain = c.minute().get()) <= 0); // 空クレジットなら捨ててやり直し

            double refund = c.price() * remain; // 返金額
            // (単価/単位=ナノ秒単価)*経過時間 == ナノ秒単位消費金額。除算を後回しにすることで浮動小数の演算誤差を減らすことを意図した
            refund -= (c.price() * nano) / ((double) TimeUnit.MINUTES.toNanos(1));

            Map<OfflinePlayer, Double> ret = new HashMap<>();
            ret.put(c.payer(), refund);
            // 残クレジットまとめて入れる
            while ((c = credit.pollFirst()) != null) {
                if (c.payer() != null) {
                    ret.merge(c.payer(), c.price() * c.minute().get(), Double::sum);
                }
            }

            return ret;
        }
    }

    private static final record Credit(double price, AtomicInteger minute, /*nullable*/ OfflinePlayer payer, Integer boughtMinute) {
        private Credit {
            if (Double.compare(price, 0.0) < 0) throw new IllegalArgumentException("price is negative"); // -0.0も弾く
            if (!Double.isFinite(price)) throw new IllegalArgumentException("price is not finite");
            if (minute == null) throw new IllegalArgumentException("minute is null");
            if (minute.get() <= 0) throw new IllegalArgumentException("minute is negative");
        }

        private Credit(double price, int minute, OfflinePlayer payer) {
            this(price, new AtomicInteger(minute), payer, minute);
        }
    }

    public /*static*/ final class FlyQuitListener implements Listener {
        private final Consumer<Player> quitHandler;

        public FlyQuitListener(Consumer<Player> quitHandler) {
            this.quitHandler = quitHandler;
        }

        @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
        public void onPlayerQuit(PlayerQuitEvent e) {
//            if (!FlyRepository.this.stopRefund(e.getPlayer(), false)) {
//                // falseの時 -> 既に削除された後疑惑があるのでquitHandlerを発動させる
//                quitHandler.accept(e.getPlayer());
//            }
            stopFlyTemporary(e.getPlayer());
        }

        @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
        public void onPlayerJoin(PlayerJoinEvent e) {
            World world = e.getPlayer().getWorld();
            if (!isFlyDisabledWorld(world.getName()))
                resumeFlyTemporary(e.getPlayer());
        }

    }
}
