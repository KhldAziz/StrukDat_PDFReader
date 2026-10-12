import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import javax.swing.*;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.Highlighter;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetDropEvent;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/**
 * PDF Tool - aplikasi GUI sederhana (Java Swing + Apache PDFBox)
 *
 * Fitur:
 *  - Buka PDF (tombol atau drag & drop)
 *  - Baca semua halaman atau rentang halaman tertentu
 *  - Simpan hasil ke file TXT (UTF-8)
 *  - Buka & edit file TXT
 *  - Cari teks (highlight semua + next/prev)
 *  - Statistik (halaman, kata, karakter, baris)
 *  - Salin semua teks ke clipboard
 *  - Ubah ukuran font, mode gelap, word wrap
 */
public class PdfToolApp extends JFrame {

    private final JTextArea textArea = new JTextArea();
    private final JLabel statusBar = new JLabel(" Siap. Buka PDF atau drag & drop file ke jendela ini.");
    private final JSpinner startPage = new JSpinner(new SpinnerNumberModel(1, 1, 1, 1));
    private final JSpinner endPage = new JSpinner(new SpinnerNumberModel(1, 1, 1, 1));
    private final JCheckBox allPages = new JCheckBox("Semua halaman", true);
    private final JTextField searchField = new JTextField(15);
    private final JProgressBar progress = new JProgressBar();

    private File currentPdf;
    private int totalPages = 0;
    private boolean dark = false;
    private int fontSize = 14;
    private int lastSearchPos = 0;

    public PdfToolApp() {
        super("PDF Tool - Baca PDF & Simpan TXT");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(1000, 700);
        setLocationRelativeTo(null);

        buildMenu();
        buildUI();
        setupDragAndDrop();
        applyTheme();
    }

    // ---------------------------------------------------------------- UI

    private void buildMenu() {
        JMenuBar bar = new JMenuBar();

        JMenu file = new JMenu("File");
        file.add(item("Buka PDF...", e -> openPdf(), KeyStroke.getKeyStroke("control O")));
        file.add(item("Buka TXT...", e -> openTxt(), KeyStroke.getKeyStroke("control shift O")));
        file.add(item("Simpan sebagai TXT...", e -> saveTxt(), KeyStroke.getKeyStroke("control S")));
        file.addSeparator();
        file.add(item("Keluar", e -> System.exit(0), null));

        JMenu edit = new JMenu("Edit");
        edit.add(item("Salin semua", e -> copyAll(), null));
        edit.add(item("Bersihkan", e -> clearAll(), null));
        edit.add(item("Cari...", e -> searchField.requestFocus(), KeyStroke.getKeyStroke("control F")));

        JMenu view = new JMenu("Tampilan");
        view.add(item("Perbesar font", e -> changeFont(+2), KeyStroke.getKeyStroke("control EQUALS")));
        view.add(item("Perkecil font", e -> changeFont(-2), KeyStroke.getKeyStroke("control MINUS")));
        JCheckBoxMenuItem wrap = new JCheckBoxMenuItem("Word wrap", true);
        wrap.addActionListener(e -> {
            textArea.setLineWrap(wrap.isSelected());
            textArea.setWrapStyleWord(wrap.isSelected());
        });
        view.add(wrap);
        JCheckBoxMenuItem darkItem = new JCheckBoxMenuItem("Mode gelap");
        darkItem.addActionListener(e -> {
            dark = darkItem.isSelected();
            applyTheme();
        });
        view.add(darkItem);

        JMenu tools = new JMenu("Alat");
        tools.add(item("Statistik teks", e -> showStats(), null));
        tools.add(item("Huruf besar semua", e -> textArea.setText(textArea.getText().toUpperCase()), null));
        tools.add(item("Huruf kecil semua", e -> textArea.setText(textArea.getText().toLowerCase()), null));
        tools.add(item("Hapus baris kosong", e -> removeEmptyLines(), null));
        tools.add(item("Gabung baris (hapus line-break)", e -> joinLines(), null));

        JMenu help = new JMenu("Bantuan");
        help.add(item("Tentang", e -> JOptionPane.showMessageDialog(this,
                "PDF Tool\nJava Swing + Apache PDFBox\n\nTips: drag & drop file PDF langsung ke jendela.",
                "Tentang", JOptionPane.INFORMATION_MESSAGE), null));

        bar.add(file);
        bar.add(edit);
        bar.add(view);
        bar.add(tools);
        bar.add(help);
        setJMenuBar(bar);
    }

    private JMenuItem item(String text, java.awt.event.ActionListener l, KeyStroke ks) {
        JMenuItem mi = new JMenuItem(text);
        mi.addActionListener(l);
        if (ks != null) mi.setAccelerator(ks);
        return mi;
    }

    private void buildUI() {
        // Toolbar atas
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));

        JButton btnOpen = new JButton("Buka PDF");
        btnOpen.addActionListener(e -> openPdf());
        JButton btnRead = new JButton("Baca / Ekstrak");
        btnRead.addActionListener(e -> extractCurrent());
        JButton btnSave = new JButton("Simpan TXT");
        btnSave.addActionListener(e -> saveTxt());
        JButton btnCopy = new JButton("Salin");
        btnCopy.addActionListener(e -> copyAll());

        allPages.addActionListener(e -> {
            boolean r = !allPages.isSelected();
            startPage.setEnabled(r);
            endPage.setEnabled(r);
        });
        startPage.setEnabled(false);
        endPage.setEnabled(false);

        top.add(btnOpen);
        top.add(btnRead);
        top.add(btnSave);
        top.add(btnCopy);
        top.add(new JSeparator(SwingConstants.VERTICAL));
        top.add(allPages);
        top.add(new JLabel("Dari:"));
        top.add(startPage);
        top.add(new JLabel("Sampai:"));
        top.add(endPage);

        // Toolbar cari
        JPanel search = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        JButton btnPrev = new JButton("◀");
        JButton btnNext = new JButton("▶");
        JButton btnHl = new JButton("Highlight semua");
        btnPrev.addActionListener(e -> findNext(false));
        btnNext.addActionListener(e -> findNext(true));
        btnHl.addActionListener(e -> highlightAll());
        searchField.addActionListener(e -> findNext(true));
        search.add(new JLabel("Cari:"));
        search.add(searchField);
        search.add(btnPrev);
        search.add(btnNext);
        search.add(btnHl);

        JPanel north = new JPanel(new BorderLayout());
        north.add(top, BorderLayout.NORTH);
        north.add(search, BorderLayout.SOUTH);

        // Area teks
        textArea.setLineWrap(true);
        textArea.setWrapStyleWord(true);
        textArea.setMargin(new Insets(8, 8, 8, 8));
        JScrollPane scroll = new JScrollPane(textArea);

        // Status bar
        progress.setIndeterminate(false);
        progress.setVisible(false);
        progress.setPreferredSize(new Dimension(150, 14));
        JPanel south = new JPanel(new BorderLayout());
        south.add(statusBar, BorderLayout.CENTER);
        south.add(progress, BorderLayout.EAST);
        south.setBorder(BorderFactory.createEmptyBorder(3, 4, 3, 4));

        setLayout(new BorderLayout());
        add(north, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
        add(south, BorderLayout.SOUTH);
    }

    private void setupDragAndDrop() {
        new DropTarget(this, new java.awt.dnd.DropTargetAdapter() {
            @Override
            @SuppressWarnings("unchecked")
            public void drop(DropTargetDropEvent dtde) {
                try {
                    dtde.acceptDrop(DnDConstants.ACTION_COPY);
                    List<File> files = (List<File>) dtde.getTransferable()
                            .getTransferData(DataFlavor.javaFileListFlavor);
                    if (!files.isEmpty()) {
                        File f = files.get(0);
                        String n = f.getName().toLowerCase();
                        if (n.endsWith(".pdf")) loadPdf(f);
                        else if (n.endsWith(".txt")) loadTxt(f);
                        else setStatus("Format tidak didukung: " + f.getName());
                    }
                } catch (Exception ex) {
                    showError("Gagal drag & drop", ex);
                }
            }
        });
    }

    private void applyTheme() {
        Color bg = dark ? new Color(30, 30, 30) : Color.WHITE;
        Color fg = dark ? new Color(220, 220, 220) : Color.BLACK;
        textArea.setBackground(bg);
        textArea.setForeground(fg);
        textArea.setCaretColor(fg);
        textArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, fontSize));
    }

    private void changeFont(int delta) {
        fontSize = Math.max(8, Math.min(48, fontSize + delta));
        applyTheme();
    }

    // ---------------------------------------------------------------- PDF

    private void openPdf() {
        JFileChooser fc = new JFileChooser();
        fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("File PDF", "pdf"));
        if (currentPdf != null) fc.setCurrentDirectory(currentPdf.getParentFile());
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            loadPdf(fc.getSelectedFile());
        }
    }

    /** Baca jumlah halaman dulu, lalu ekstrak semua teks. */
    private void loadPdf(File file) {
        try (PDDocument doc = Loader.loadPDF(file)) {
            currentPdf = file;
            totalPages = doc.getNumberOfPages();
            ((SpinnerNumberModel) startPage.getModel()).setMaximum(totalPages);
            ((SpinnerNumberModel) endPage.getModel()).setMaximum(totalPages);
            startPage.setValue(1);
            endPage.setValue(totalPages);
            setTitle("PDF Tool - " + file.getName());
        } catch (Exception ex) {
            showError("Gagal membuka PDF (mungkin terenkripsi / rusak)", ex);
            return;
        }
        extractCurrent();
    }

    private void extractCurrent() {
        if (currentPdf == null) {
            setStatus("Belum ada PDF yang dibuka.");
            return;
        }
        final int from = allPages.isSelected() ? 1 : (int) startPage.getValue();
        final int to = allPages.isSelected() ? totalPages : (int) endPage.getValue();
        if (from > to) {
            JOptionPane.showMessageDialog(this, "Halaman awal tidak boleh lebih besar dari halaman akhir.");
            return;
        }

        progress.setVisible(true);
        progress.setIndeterminate(true);
        setStatus("Membaca " + currentPdf.getName() + " (hal. " + from + "-" + to + ")...");

        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() throws Exception {
                try (PDDocument doc = Loader.loadPDF(currentPdf)) {
                    PDFTextStripper stripper = new PDFTextStripper();
                    stripper.setStartPage(from);
                    stripper.setEndPage(to);
                    stripper.setSortByPosition(true);
                    return stripper.getText(doc);
                }
            }

            @Override
            protected void done() {
                progress.setVisible(false);
                try {
                    String text = get();
                    textArea.setText(text);
                    textArea.setCaretPosition(0);
                    lastSearchPos = 0;
                    if (text.trim().isEmpty()) {
                        setStatus("Selesai, tapi tidak ada teks. PDF mungkin hasil scan (butuh OCR).");
                    } else {
                        setStatus("Selesai: " + currentPdf.getName() + " | " + totalPages
                                + " halaman | " + countWords(text) + " kata");
                    }
                } catch (Exception ex) {
                    showError("Gagal membaca PDF", ex);
                }
            }
        }.execute();
    }

    // ---------------------------------------------------------------- TXT

    private void saveTxt() {
        if (textArea.getText().isEmpty()) {
            JOptionPane.showMessageDialog(this, "Tidak ada teks untuk disimpan.");
            return;
        }
        JFileChooser fc = new JFileChooser();
        fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("File teks (*.txt)", "txt"));
        if (currentPdf != null) {
            String base = currentPdf.getName().replaceAll("(?i)\\.pdf$", "");
            fc.setSelectedFile(new File(currentPdf.getParentFile(), base + ".txt"));
        }
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;

        File out = fc.getSelectedFile();
        if (!out.getName().toLowerCase().endsWith(".txt")) {
            out = new File(out.getParentFile(), out.getName() + ".txt");
        }
        if (out.exists() && JOptionPane.showConfirmDialog(this,
                out.getName() + " sudah ada. Timpa?", "Konfirmasi",
                JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) {
            return;
        }
        try {
            Files.writeString(out.toPath(), textArea.getText(), StandardCharsets.UTF_8);
            setStatus("Tersimpan: " + out.getAbsolutePath());
        } catch (Exception ex) {
            showError("Gagal menyimpan file", ex);
        }
    }

    private void openTxt() {
        JFileChooser fc = new JFileChooser();
        fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("File teks (*.txt)", "txt"));
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            loadTxt(fc.getSelectedFile());
        }
    }

    private void loadTxt(File f) {
        try {
            textArea.setText(Files.readString(f.toPath(), StandardCharsets.UTF_8));
            textArea.setCaretPosition(0);
            setStatus("Dibuka: " + f.getAbsolutePath());
        } catch (Exception ex) {
            showError("Gagal membuka TXT", ex);
        }
    }

    // ---------------------------------------------------------------- Fitur lain

    private void copyAll() {
        Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(textArea.getText()), null);
        setStatus("Teks disalin ke clipboard.");
    }

    private void clearAll() {
        textArea.setText("");
        textArea.getHighlighter().removeAllHighlights();
        setStatus("Dibersihkan.");
    }

    private void showStats() {
        String t = textArea.getText();
        int lines = t.isEmpty() ? 0 : t.split("\r?\n", -1).length;
        String msg = String.format(
                "Halaman PDF : %s%nKata        : %d%nKarakter    : %d%nKarakter (tanpa spasi): %d%nBaris       : %d",
                currentPdf == null ? "-" : String.valueOf(totalPages),
                countWords(t), t.length(), t.replaceAll("\\s", "").length(), lines);
        JOptionPane.showMessageDialog(this, msg, "Statistik", JOptionPane.INFORMATION_MESSAGE);
    }

    private int countWords(String t) {
        String s = t.trim();
        return s.isEmpty() ? 0 : s.split("\\s+").length;
    }

    private void removeEmptyLines() {
        textArea.setText(textArea.getText().replaceAll("(?m)^[ \\t]*\\r?\\n", ""));
        setStatus("Baris kosong dihapus.");
    }

    /** Berguna untuk PDF yang tiap barisnya terpotong. Paragraf (baris kosong) dipertahankan. */
    private void joinLines() {
        String t = textArea.getText().replace("\r\n", "\n");
        t = t.replaceAll("(?<!\\n)\\n(?!\\n)", " ");
        textArea.setText(t);
        setStatus("Baris digabung.");
    }

    // ---------------------------------------------------------------- Pencarian

    private void highlightAll() {
        Highlighter h = textArea.getHighlighter();
        h.removeAllHighlights();
        String q = searchField.getText();
        if (q.isEmpty()) return;
        String text = textArea.getText().toLowerCase();
        String key = q.toLowerCase();
        int idx = 0, count = 0;
        Highlighter.HighlightPainter p =
                new DefaultHighlighter.DefaultHighlightPainter(new Color(255, 214, 0, 160));
        try {
            while ((idx = text.indexOf(key, idx)) >= 0) {
                h.addHighlight(idx, idx + key.length(), p);
                idx += key.length();
                count++;
            }
        } catch (BadLocationException ignored) {
        }
        setStatus(count + " hasil ditemukan untuk \"" + q + "\"");
    }

    private void findNext(boolean forward) {
        String q = searchField.getText().toLowerCase();
        if (q.isEmpty()) return;
        String text = textArea.getText().toLowerCase();
        int pos;
        if (forward) {
            pos = text.indexOf(q, textArea.getSelectionEnd());
            if (pos < 0) pos = text.indexOf(q); // balik ke awal
        } else {
            int from = Math.max(0, textArea.getSelectionStart() - 1);
            pos = text.lastIndexOf(q, from);
            if (pos < 0) pos = text.lastIndexOf(q); // lompat ke akhir
        }
        if (pos >= 0) {
            textArea.requestFocus();
            textArea.select(pos, pos + q.length());
            setStatus("Ditemukan di posisi " + pos);
        } else {
            setStatus("Tidak ditemukan: \"" + searchField.getText() + "\"");
        }
    }

    // ---------------------------------------------------------------- Util

    private void setStatus(String s) {
        statusBar.setText(" " + s);
    }

    private void showError(String title, Exception ex) {
        ex.printStackTrace();
        JOptionPane.showMessageDialog(this, title + ":\n" + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        setStatus("Error: " + ex.getMessage());
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
            }
            new PdfToolApp().setVisible(true);
        });
    }
}