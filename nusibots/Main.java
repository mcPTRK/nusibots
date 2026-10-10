package bots;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.awt.event.*;
import java.util.HashMap;
import java.util.Map;

public class Main extends JFrame {
    static final Color BG = new Color(0x11, 0x13, 0x18), PANEL = new Color(0x1a, 0x1d, 0x25), CARD = new Color(0x25, 0x29, 0x34),
            TXT = new Color(0xe6, 0xe8, 0xee), MUTED = new Color(0x8b, 0x91, 0xa3), ACC = new Color(0x5b, 0x8c, 0xff),
            GOOD = new Color(0x43, 0xd1, 0x7a), BAD = new Color(0xff, 0x5c, 0x6c), WARN = new Color(0xf5, 0xc1, 0x4e);
    static final Font UI = new Font("SansSerif", Font.PLAIN, 13), BOLD = new Font("SansSerif", Font.BOLD, 13);

    final DefaultListModel<Bot> model = new DefaultListModel<>();
    final JList<Bot> list = new JList<>(model);
    final JTextField host = field("nusi.pl"), port = field("25565"), pass = field("haslo123"), newName = field("Bot1");
    final JCheckBox relog = check("Auto-relog po rozłączeniu", true);
    final JCheckBox kbd = check("Sterowanie klawiaturą (W A S D, Spacja, strzałki)", true);
    final JTextArea chatArea = new JTextArea();
    final JTextField chatInput = field("");
    final JLabel hp = label(""), pos = label(""), rot = label(""), heldLbl = label(""), status = label("");
    final InvPanel inv = new InvPanel();
    Bot shown;
    int shownEpoch, shownCount;

    public static void main(String[] a) {
        try { UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName()); } catch (Exception ignored) {}
        SwingUtilities.invokeLater(() -> new Main().setVisible(true));
    }

    // ---------- pomocnicze elementy UI ----------
    static JLabel label(String t) { JLabel l = new JLabel(t); l.setForeground(TXT); l.setFont(UI); return l; }
    static JLabel muted(String t) { JLabel l = label(t); l.setForeground(MUTED); return l; }
    static JTextField field(String t) {
        JTextField f = new JTextField(t);
        f.setBackground(CARD); f.setForeground(TXT); f.setCaretColor(TXT); f.setFont(UI);
        f.setBorder(new javax.swing.border.CompoundBorder(new LineBorder(new Color(0x38, 0x3d, 0x4c), 1, true), new EmptyBorder(6, 8, 6, 8)));
        return f;
    }
    static JCheckBox check(String t, boolean sel) {
        JCheckBox c = new JCheckBox(t, sel);
        c.setOpaque(false); c.setForeground(TXT); c.setFont(UI); c.setFocusPainted(false);
        return c;
    }

    static class Btn extends JButton {
        final Color base;
        Btn(String t, Color c) {
            super(t);
            base = c;
            setContentAreaFilled(false); setFocusPainted(false); setBorderPainted(false); setRolloverEnabled(true);
            setForeground(Color.WHITE); setFont(BOLD); setBorder(new EmptyBorder(8, 14, 8, 14));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }
        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color c = !isEnabled() ? base.darker().darker() : getModel().isPressed() ? base.darker() : getModel().isRollover() ? base.brighter() : base;
            g2.setColor(c);
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), 12, 12);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    static JPanel col(int gap) {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setOpaque(false);
        return p;
    }

    static JScrollPane scroll(Component c) {
        JScrollPane s = new JScrollPane(c);
        s.setBorder(new LineBorder(new Color(0x2c, 0x31, 0x3e), 1, true));
        s.getViewport().setBackground(CARD);
        s.setBackground(CARD);
        return s;
    }

    Bot sel() { return list.getSelectedValue(); }

    // ---------- budowa okna ----------
    Main() {
        super("Nusi Bots – menedżer botów (1.21.4)");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(1060, 700);
        setMinimumSize(new Dimension(900, 600));
        setLocationRelativeTo(null);
        getContentPane().setBackground(BG);
        setLayout(new BorderLayout(12, 12));
        ((JComponent) getContentPane()).setBorder(new EmptyBorder(12, 12, 12, 12));

        add(buildLeft(), BorderLayout.WEST);
        add(buildRight(), BorderLayout.CENTER);

        status.setForeground(MUTED);
        add(status, BorderLayout.SOUTH);

        installKeys();
        new javax.swing.Timer(200, e -> refresh()).start();
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { for (int i = 0; i < model.size(); i++) model.get(i).shutdown(); }
        });
    }

    JComponent buildLeft() {
        JPanel p = new JPanel(new BorderLayout(0, 10));
        p.setBackground(PANEL);
        p.setBorder(new EmptyBorder(14, 14, 14, 14));
        p.setPreferredSize(new Dimension(300, 0));

        JPanel top = col(6);
        JLabel title = new JLabel("Boty");
        title.setForeground(TXT); title.setFont(new Font("SansSerif", Font.BOLD, 22));
        top.add(title);
        top.add(Box.createVerticalStrut(10));
        top.add(muted("Adres serwera"));
        JPanel hp1 = new JPanel(new BorderLayout(6, 0));
        hp1.setOpaque(false);
        hp1.add(host, BorderLayout.CENTER);
        port.setPreferredSize(new Dimension(70, 30));
        hp1.add(port, BorderLayout.EAST);
        hp1.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
        top.add(hp1);
        top.add(Box.createVerticalStrut(8));
        top.add(muted("Hasło (do /register i /login)"));
        pass.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
        top.add(pass);
        top.add(Box.createVerticalStrut(8));
        top.add(relog);
        top.add(Box.createVerticalStrut(10));
        top.add(muted("Nick nowego bota"));
        JPanel add = new JPanel(new BorderLayout(6, 0));
        add.setOpaque(false);
        add.add(newName, BorderLayout.CENTER);
        Btn addBtn = new Btn("Dodaj", ACC);
        addBtn.addActionListener(e -> addBot());
        add.add(addBtn, BorderLayout.EAST);
        add.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
        top.add(add);
        p.add(top, BorderLayout.NORTH);

        list.setBackground(CARD);
        list.setFixedCellHeight(34);
        list.setSelectionBackground(new Color(0x32, 0x3a, 0x52));
        list.setCellRenderer((l, v, i, selected, f) -> {
            JLabel lb = new JLabel("  ●  " + v.name + "   " + stateText(v));
            lb.setOpaque(true);
            lb.setFont(BOLD);
            lb.setBackground(selected ? l.getSelectionBackground() : CARD);
            lb.setForeground(stateColor(v));
            return lb;
        });
        list.addListSelectionListener(e -> refresh());
        p.add(scroll(list), BorderLayout.CENTER);

        JPanel bottom = new JPanel(new GridLayout(1, 3, 6, 0));
        bottom.setOpaque(false);
        Btn con = new Btn("Połącz", GOOD.darker());
        Btn dis = new Btn("Rozłącz", WARN.darker());
        Btn del = new Btn("Usuń", BAD.darker());
        con.addActionListener(e -> {
            Bot b = sel();
            if (b == null) return;
            b.host = host.getText().trim();
            try { b.port = Integer.parseInt(port.getText().trim()); } catch (NumberFormatException ex) { b.port = 25565; }
            b.password = pass.getText();
            b.autoRelog = relog.isSelected();
            b.connect();
        });
        dis.addActionListener(e -> { Bot b = sel(); if (b != null) b.disconnect(); });
        del.addActionListener(e -> { Bot b = sel(); if (b != null) { b.shutdown(); model.removeElement(b); } });
        bottom.add(con); bottom.add(dis); bottom.add(del);
        p.add(bottom, BorderLayout.SOUTH);
        relog.addActionListener(e -> { Bot b = sel(); if (b != null) b.autoRelog = relog.isSelected(); });
        return p;
    }

    JComponent buildRight() {
        JTabbedPane tabs = new JTabbedPane();
        tabs.setBackground(PANEL);
        tabs.setForeground(TXT);
        tabs.setFont(BOLD);
        tabs.addTab("Czat i log", buildChat());
        tabs.addTab("Sterowanie", buildControl());
        tabs.addTab("Ekwipunek", buildInv());
        return tabs;
    }

    JComponent buildChat() {
        JPanel p = new JPanel(new BorderLayout(0, 10));
        p.setBackground(PANEL);
        p.setBorder(new EmptyBorder(12, 12, 12, 12));
        chatArea.setEditable(false); chatArea.setLineWrap(true); chatArea.setWrapStyleWord(true);
        chatArea.setBackground(CARD); chatArea.setForeground(TXT); chatArea.setFont(new Font("Monospaced", Font.PLAIN, 13));
        chatArea.setBorder(new EmptyBorder(8, 8, 8, 8));
        p.add(scroll(chatArea), BorderLayout.CENTER);
        JPanel in = new JPanel(new BorderLayout(8, 0));
        in.setOpaque(false);
        in.add(chatInput, BorderLayout.CENTER);
        Btn send = new Btn("Wyślij", ACC);
        in.add(send, BorderLayout.EAST);
        p.add(in, BorderLayout.SOUTH);
        Runnable go = () -> {
            Bot b = sel();
            String t = chatInput.getText().trim();
            if (b == null || t.isEmpty()) return;
            b.sendChat(t);
            chatInput.setText("");
        };
        send.addActionListener(e -> go.run());
        chatInput.addActionListener(e -> go.run());
        return p;
    }

    Btn hold(String t, java.util.function.BiConsumer<Bot, Boolean> action) {
        Btn b = new Btn(t, CARD.brighter());
        b.getModel().addChangeListener(e -> {
            Bot bot = sel();
            if (bot != null) action.accept(bot, b.getModel().isPressed());
        });
        return b;
    }

    Btn click(String t, java.util.function.Consumer<Bot> action) {
        Btn b = new Btn(t, CARD.brighter());
        b.addActionListener(e -> { Bot bot = sel(); if (bot != null) action.accept(bot); });
        return b;
    }

    JComponent buildControl() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBackground(PANEL);
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(5, 5, 5, 5);
        c.fill = GridBagConstraints.BOTH;
        c.weightx = 1;

        // info
        JPanel info = new JPanel(new GridLayout(0, 1, 0, 3));
        info.setBackground(CARD);
        info.setBorder(new EmptyBorder(10, 12, 10, 12));
        info.add(hp); info.add(pos); info.add(rot); info.add(heldLbl);
        c.gridx = 0; c.gridy = 0; c.gridwidth = 3;
        p.add(info, c);

        c.gridwidth = 1;
        // WASD
        c.gridy = 1; c.gridx = 1; p.add(hold("W", (b, d) -> b.fw = d), c);
        c.gridy = 2; c.gridx = 0; p.add(hold("A", (b, d) -> b.lf = d), c);
        c.gridx = 1; p.add(hold("S", (b, d) -> b.bk = d), c);
        c.gridx = 2; p.add(hold("D", (b, d) -> b.rt = d), c);
        c.gridy = 3; c.gridx = 0; c.gridwidth = 3;
        p.add(click("Skok (Spacja)", Bot::jump), c);

        // obrót
        JPanel rotP = new JPanel(new GridLayout(1, 5, 6, 0));
        rotP.setOpaque(false);
        rotP.add(click("◀ Lewo", b -> b.rotate(-15, 0)));
        rotP.add(click("▲ Góra", b -> b.rotate(0, -10)));
        rotP.add(click("▼ Dół", b -> b.rotate(0, 10)));
        rotP.add(click("Prawo ▶", b -> b.rotate(15, 0)));
        rotP.add(click("Zawróć", b -> b.rotate(180, 0)));
        c.gridy = 4; p.add(rotP, c);

        // pasek szybkiego dostępu
        JPanel hot = new JPanel(new GridLayout(1, 9, 4, 0));
        hot.setOpaque(false);
        for (int i = 0; i < 9; i++) {
            final int n = i;
            hot.add(click(String.valueOf(i + 1), b -> b.selectSlot(n)));
        }
        c.gridy = 5; p.add(muted("Slot paska (co bot trzyma)"), c);
        c.gridy = 6; p.add(hot, c);

        JPanel act = new JPanel(new GridLayout(1, 3, 6, 0));
        act.setOpaque(false);
        act.add(click("Wyrzuć 1 szt.", b -> b.dropHeld(false)));
        act.add(click("Wyrzuć cały stack", b -> b.dropHeld(true)));
        act.add(click("STOP ruchu", Bot::stopMoving));
        c.gridy = 7; p.add(act, c);

        c.gridy = 8; p.add(kbd, c);
        c.gridy = 9; c.weighty = 1; p.add(Box.createGlue(), c);
        return p;
    }

    JComponent buildInv() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(PANEL);
        p.setBorder(new EmptyBorder(12, 12, 12, 12));
        p.add(inv, BorderLayout.CENTER);
        return p;
    }

    // ---------- logika ----------
    void addBot() {
        String n = newName.getText().trim();
        if (!n.matches("[A-Za-z0-9_]{3,16}")) {
            JOptionPane.showMessageDialog(this, "Nick: 3–16 znaków (litery, cyfry, _).");
            return;
        }
        for (int i = 0; i < model.size(); i++) if (model.get(i).name.equalsIgnoreCase(n)) {
            JOptionPane.showMessageDialog(this, "Bot o takim nicku już jest.");
            return;
        }
        int port0;
        try { port0 = Integer.parseInt(port.getText().trim()); } catch (NumberFormatException e) { port0 = 25565; }
        Bot b = new Bot(n, host.getText().trim(), port0, pass.getText());
        b.autoRelog = relog.isSelected();
        model.addElement(b);
        list.setSelectedValue(b, true);
        newName.setText(n.replaceAll("\\d+$", "") + (model.size() + 1));
    }

    static String stateText(Bot b) {
        switch (b.state) {
            case PLAY: return "online";
            case OFFLINE: return "offline";
            case CONNECTING: return "łączenie...";
            case LOGIN: return "logowanie...";
            default: return "konfiguracja...";
        }
    }

    static Color stateColor(Bot b) {
        switch (b.state) {
            case PLAY: return GOOD;
            case OFFLINE: return MUTED;
            default: return WARN;
        }
    }

    void refresh() {
        list.repaint();
        Bot b = sel();
        if (b == null) {
            status.setText("  Dodaj bota po lewej, wybierz go z listy i kliknij „Połącz”.");
            hp.setText("Brak wybranego bota");
            pos.setText(""); rot.setText(""); heldLbl.setText("");
            inv.repaint();
            return;
        }
        // log
        if (b != shown || b.logEpoch != shownEpoch) {
            StringBuilder sb = new StringBuilder();
            synchronized (b.logLines) { for (String s : b.logLines) sb.append(s).append('\n'); shownCount = b.logLines.size(); }
            chatArea.setText(sb.toString());
            chatArea.setCaretPosition(chatArea.getDocument().getLength());
            shown = b;
            shownEpoch = b.logEpoch;
        } else {
            synchronized (b.logLines) {
                if (b.logLines.size() > shownCount) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = shownCount; i < b.logLines.size(); i++) sb.append(b.logLines.get(i)).append('\n');
                    shownCount = b.logLines.size();
                    chatArea.append(sb.toString());
                    chatArea.setCaretPosition(chatArea.getDocument().getLength());
                }
            }
        }
        hp.setText(String.format("%s  –  ❤ %.1f / 20    🍗 %d / 20", b.name, b.health, b.food));
        pos.setText(String.format("Pozycja:  X %.2f   Y %.2f   Z %.2f", b.x, b.y, b.z));
        rot.setText(String.format("Obrót:  yaw %.0f°   pitch %.0f°", b.yaw, b.pitch));
        Bot.Slot h = b.heldItem();
        heldLbl.setText("Trzyma (slot " + (b.held + 1) + "):  " + (b.unknown[36 + b.held] ? "?" : h == null ? "pusta ręka" : "przedmiot #" + h.id + "  ×" + h.count));
        status.setText("  " + b.name + ": " + stateText(b) + "   |   " + b.host + ":" + b.port);
        inv.repaint();
    }

    void installKeys() {
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(e -> {
            if (!isActive() || e.getID() == KeyEvent.KEY_TYPED) return false;
            Bot b = sel();
            if (b == null) return false;
            boolean down = e.getID() == KeyEvent.KEY_PRESSED;
            int k = e.getKeyCode();
            boolean wasd = k == KeyEvent.VK_W || k == KeyEvent.VK_A || k == KeyEvent.VK_S || k == KeyEvent.VK_D;
            if (!down && wasd) { // puszczenie zawsze zatrzymuje ruch
                if (k == KeyEvent.VK_W) b.fw = false;
                if (k == KeyEvent.VK_A) b.lf = false;
                if (k == KeyEvent.VK_S) b.bk = false;
                if (k == KeyEvent.VK_D) b.rt = false;
                return false;
            }
            if (!kbd.isSelected() || KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner() instanceof JTextComponent) return false;
            switch (k) {
                case KeyEvent.VK_W: b.fw = true; return true;
                case KeyEvent.VK_A: b.lf = true; return true;
                case KeyEvent.VK_S: b.bk = true; return true;
                case KeyEvent.VK_D: b.rt = true; return true;
                case KeyEvent.VK_SPACE: if (down) b.jump(); return true;
                case KeyEvent.VK_LEFT: if (down) b.rotate(-10, 0); return true;
                case KeyEvent.VK_RIGHT: if (down) b.rotate(10, 0); return true;
                case KeyEvent.VK_UP: if (down) b.rotate(0, -8); return true;
                case KeyEvent.VK_DOWN: if (down) b.rotate(0, 8); return true;
                default: return false;
            }
        });
    }

    // ---------- panel ekwipunku ----------
    class InvPanel extends JPanel {
        static final int C = 54, G = 6;
        final Map<Integer, Rectangle> rects = new HashMap<>();

        InvPanel() {
            setBackground(PANEL);
            addMouseListener(new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) {
                    Bot b = sel();
                    if (b == null) return;
                    for (Map.Entry<Integer, Rectangle> en : rects.entrySet())
                        if (en.getValue().contains(e.getPoint()) && en.getKey() >= 36 && en.getKey() <= 44) b.selectSlot(en.getKey() - 36);
                }
            });
        }

        void heading(Graphics2D g, String t, int x, int y) {
            g.setColor(MUTED); g.setFont(BOLD); g.drawString(t, x, y);
        }

        @Override protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            Graphics2D g = (Graphics2D) g0;
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            rects.clear();
            Bot b = sel();
            int ox = 10, y = 14;
            heading(g, "Pancerz  /  druga ręka", ox, y + 4); y += 12;
            int[] arm = {5, 6, 7, 8};
            for (int i = 0; i < 4; i++) cell(g, b, arm[i], ox + i * (C + G), y);
            cell(g, b, 45, ox + 5 * (C + G), y);
            y += C + G + 22;
            heading(g, "Ekwipunek", ox, y); y += 8;
            for (int r = 0; r < 3; r++) for (int cc = 0; cc < 9; cc++) cell(g, b, 9 + r * 9 + cc, ox + cc * (C + G), y + r * (C + G));
            y += 3 * (C + G) + 16;
            heading(g, "Pasek szybkiego dostępu  (kliknij slot, aby go wybrać)", ox, y); y += 8;
            for (int cc = 0; cc < 9; cc++) cell(g, b, 36 + cc, ox + cc * (C + G), y);
            if (b == null) { g.setColor(MUTED); g.drawString("Wybierz bota z listy.", ox, y + C + 40); }
            else if (b.state != Bot.State.PLAY) { g.setColor(MUTED); g.drawString("Bot nie jest na serwerze – ekwipunek pojawi się po wejściu.", ox, y + C + 40); }
        }

        void cell(Graphics2D g, Bot b, int slot, int x, int y) {
            rects.put(slot, new Rectangle(x, y, C, C));
            g.setColor(CARD);
            g.fillRoundRect(x, y, C, C, 10, 10);
            boolean isHeld = b != null && slot == 36 + b.held;
            if (isHeld) { g.setColor(ACC); g.setStroke(new BasicStroke(3f)); g.drawRoundRect(x + 1, y + 1, C - 3, C - 3, 10, 10); g.setStroke(new BasicStroke(1f)); }
            if (b == null) return;
            if (b.unknown[slot]) { g.setColor(MUTED); g.setFont(BOLD); g.drawString("?", x + C / 2 - 4, y + C / 2 + 5); return; }
            Bot.Slot s = b.slots[slot];
            if (s == null) return;
            g.setColor(Color.getHSBColor((float) ((s.id * 0.61803) % 1.0), 0.55f, 0.85f));
            g.fillRoundRect(x + 9, y + 8, C - 18, C - 22, 8, 8);
            g.setColor(Color.WHITE);
            g.setFont(new Font("SansSerif", Font.BOLD, 10));
            String id = "#" + s.id;
            g.drawString(id, x + (C - g.getFontMetrics().stringWidth(id)) / 2, y + C / 2 - 1);
            if (s.count > 1) {
                g.setFont(BOLD);
                String c = String.valueOf(s.count);
                g.drawString(c, x + C - 5 - g.getFontMetrics().stringWidth(c), y + C - 6);
            }
        }
    }
}
