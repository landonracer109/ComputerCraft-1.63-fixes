package dan200.computercraft.core.terminal;

import java.util.Arrays;
import net.minecraft.nbt.NBTTagCompound;

// Thread-safety: a computer changes its terminal on the ComputerCraft thread while the server thread
// saves and sends it (writeToNBT), and resize()/scroll() swap in new line arrays and fill them in.
// Without a lock the server can see a half-built array and crash writing a null line to NBT
// ("Empty string not allowed"). All public methods are synchronized, as in later ComputerCraft.
public class Terminal {
    private static final String base16 = "0123456789abcdef";
    private int m_cursorX;
    private int m_cursorY;
    private boolean m_cursorBlink;
    private int m_textColour;
    private int m_backgroundColour;
    private int m_width;
    private int m_height;
    private String m_emptyLine;
    private String m_emptyColourLine;
    private String[] m_lines;
    private String[] m_colourLines;
    private boolean m_changed;

    public Terminal(int width, int height) {
        this.m_width = width;
        this.m_height = height;
        this.m_textColour = 15;
        this.m_backgroundColour = 0;
        this.rebuildEmptyLine();
        this.rebuildEmptyColourLine();
        this.m_lines = new String[this.m_height];
        this.m_colourLines = new String[this.m_height];
        for (int i = 0; i < this.m_height; ++i) {
            this.m_lines[i] = this.m_emptyLine;
            this.m_colourLines[i] = this.m_emptyColourLine;
        }
        this.m_cursorX = 0;
        this.m_cursorY = 0;
        this.m_cursorBlink = false;
        this.m_changed = false;
    }

    public synchronized int getWidth() {
        return this.m_width;
    }

    public synchronized int getHeight() {
        return this.m_height;
    }

    // Length-fix: every line must be exactly m_width chars and every colour line exactly
    // 2 * m_width (text colours, then background colours). Monitor updates from the server can
    // leave them mismatched, which crashed resize() and the monitor renderer. Pad or trim them.
    private static String fit(String s, String pad, int length) {
        if (s.length() == length) {
            return s;
        }
        if (s.length() > length) {
            return s.substring(0, length);
        }
        return s + pad.substring(0, length - s.length());
    }

    private void normaliseLines() {
        String emptyText = this.m_emptyColourLine.substring(0, this.m_width);
        String emptyBackground = this.m_emptyColourLine.substring(this.m_width);
        if (this.m_lines == null || this.m_lines.length != this.m_height) {
            String[] lines = new String[this.m_height];
            String[] colourLines = new String[this.m_height];
            for (int i = 0; i < this.m_height; ++i) {
                boolean had = this.m_lines != null && this.m_colourLines != null && i < this.m_lines.length && i < this.m_colourLines.length;
                lines[i] = had ? this.m_lines[i] : null;
                colourLines[i] = had ? this.m_colourLines[i] : null;
            }
            this.m_lines = lines;
            this.m_colourLines = colourLines;
        }
        for (int i = 0; i < this.m_height; ++i) {
            String line = this.m_lines[i];
            this.m_lines[i] = line == null ? this.m_emptyLine : fit(line, this.m_emptyLine, this.m_width);
            String colour = this.m_colourLines[i];
            if (colour == null || colour.length() % 2 != 0) {
                this.m_colourLines[i] = this.m_emptyColourLine;
            } else if (colour.length() != this.m_width * 2) {
                int half = colour.length() / 2;
                this.m_colourLines[i] = fit(colour.substring(0, half), emptyText, this.m_width) + fit(colour.substring(half), emptyBackground, this.m_width);
            }
        }
    }

    public synchronized void resize(int width, int height) {
        if (width == this.m_width && height == this.m_height) {
            return;
        }
        this.normaliseLines();
        int oldHeight = this.m_height;
        int oldWidth = this.m_width;
        String[] oldLines = this.m_lines;
        String[] oldColourLines = this.m_colourLines;
        this.m_width = width;
        this.m_height = height;
        this.rebuildEmptyLine();
        this.rebuildEmptyColourLine();
        this.m_lines = new String[this.m_height];
        this.m_colourLines = new String[this.m_height];
        for (int i = 0; i < this.m_height; ++i) {
            if (i < oldHeight) {
                String bgColourLine;
                String textColourLine;
                String oldLine = oldLines[i];
                String oldTextColourLine = oldColourLines[i].substring(0, oldWidth);
                String oldBgColourLine = oldColourLines[i].substring(oldWidth, oldWidth * 2);
                if (oldLine.length() >= this.m_width) {
                    this.m_lines[i] = oldLine.substring(0, this.m_width);
                    textColourLine = oldTextColourLine.substring(0, this.m_width);
                    bgColourLine = oldBgColourLine.substring(0, this.m_width);
                } else {
                    this.m_lines[i] = oldLine + this.m_emptyLine.substring(oldLine.length(), this.m_width);
                    textColourLine = oldTextColourLine + this.m_emptyColourLine.substring(oldLine.length(), this.m_width);
                    bgColourLine = oldBgColourLine + this.m_emptyColourLine.substring(this.m_width + oldLine.length(), this.m_width * 2);
                }
                this.m_colourLines[i] = textColourLine + bgColourLine;
                continue;
            }
            this.m_lines[i] = this.m_emptyLine;
            this.m_colourLines[i] = this.m_emptyColourLine;
        }
        this.m_changed = true;
    }

    public synchronized void setCursorPos(int x, int y) {
        if (this.m_cursorX != x || this.m_cursorY != y) {
            this.m_cursorX = x;
            this.m_cursorY = y;
            this.m_changed = true;
        }
    }

    public synchronized void setCursorBlink(boolean blink) {
        if (this.m_cursorBlink != blink) {
            this.m_cursorBlink = blink;
            this.m_changed = true;
        }
    }

    private void rebuildEmptyLine() {
        char[] spaces = new char[this.m_width];
        Arrays.fill(spaces, ' ');
        this.m_emptyLine = new String(spaces);
    }

    private void rebuildEmptyColourLine() {
        char textColourChar = base16.charAt(this.m_textColour);
        char[] textColourChars = new char[this.m_width];
        Arrays.fill(textColourChars, textColourChar);
        char bgColourChar = base16.charAt(this.m_backgroundColour);
        char[] bgColourChars = new char[this.m_width];
        Arrays.fill(bgColourChars, bgColourChar);
        this.m_emptyColourLine = new String(textColourChars) + new String(bgColourChars);
    }

    public synchronized void setTextColour(int colour) {
        if (this.m_textColour != colour) {
            this.m_textColour = colour;
            this.m_changed = true;
            this.rebuildEmptyColourLine();
        }
    }

    public synchronized void setBackgroundColour(int colour) {
        if (this.m_backgroundColour != colour) {
            this.m_backgroundColour = colour;
            this.m_changed = true;
            this.rebuildEmptyColourLine();
        }
    }

    public synchronized int getCursorX() {
        return this.m_cursorX;
    }

    public synchronized int getCursorY() {
        return this.m_cursorY;
    }

    public synchronized boolean getCursorBlink() {
        return this.m_cursorBlink;
    }

    public synchronized int getTextColour() {
        return this.m_textColour;
    }

    public synchronized int getBackgroundColour() {
        return this.m_backgroundColour;
    }

    public synchronized void write(String string) {
        if (this.m_cursorY >= 0 && this.m_cursorY < this.m_height) {
            int writeX = this.m_cursorX;
            int spaceLeft = this.m_width - this.m_cursorX;
            if (spaceLeft > this.m_width + string.length()) {
                return;
            }
            if (spaceLeft > this.m_width) {
                writeX = 0;
                string = string.substring(spaceLeft - this.m_width);
                spaceLeft = this.m_width;
            }
            string = string.replace('\t', ' ');
            if (spaceLeft > 0) {
                String oldLine = this.m_lines[this.m_cursorY];
                String oldColourLine = this.m_colourLines[this.m_cursorY];
                String oldTextLine = oldColourLine.substring(0, oldLine.length());
                String oldBackgroundLine = oldColourLine.substring(oldLine.length(), 2 * oldLine.length());
                StringBuilder newLine = new StringBuilder();
                StringBuilder newTextLine = new StringBuilder();
                StringBuilder newBackgroundLine = new StringBuilder();
                newLine.append(oldLine.substring(0, writeX));
                newTextLine.append(oldTextLine.substring(0, writeX));
                newBackgroundLine.append(oldBackgroundLine.substring(0, writeX));
                if (string.length() < spaceLeft) {
                    newLine.append(string);
                    newTextLine.append(this.m_emptyColourLine.substring(0, string.length()));
                    newBackgroundLine.append(this.m_emptyColourLine.substring(oldLine.length(), oldLine.length() + string.length()));
                    newLine.append(oldLine.substring(writeX + string.length()));
                    newTextLine.append(oldTextLine.substring(writeX + string.length()));
                    newBackgroundLine.append(oldBackgroundLine.substring(writeX + string.length()));
                } else {
                    newLine.append(string.substring(0, spaceLeft));
                    newTextLine.append(this.m_emptyColourLine.substring(0, spaceLeft));
                    newBackgroundLine.append(this.m_emptyColourLine.substring(oldLine.length(), oldLine.length() + spaceLeft));
                }
                this.m_lines[this.m_cursorY] = newLine.toString();
                this.m_colourLines[this.m_cursorY] = newTextLine.toString() + newBackgroundLine.toString();
                if (!this.m_changed) {
                    if (!this.m_lines[this.m_cursorY].equals(oldLine)) {
                        this.m_changed = true;
                    }
                    if (!this.m_colourLines[this.m_cursorY].equals(oldColourLine)) {
                        this.m_changed = true;
                    }
                }
            }
        }
    }

    public synchronized void scroll(int yDiff) {
        String[] newLines = new String[this.m_height];
        String[] newColourLines = new String[this.m_height];
        for (int y = 0; y < this.m_height; ++y) {
            int oldY = y + yDiff;
            if (oldY >= 0 && oldY < this.m_height) {
                newLines[y] = this.m_lines[oldY];
                newColourLines[y] = this.m_colourLines[oldY];
            } else {
                newLines[y] = this.m_emptyLine;
                newColourLines[y] = this.m_emptyColourLine;
            }
            if (!newLines[y].equals(this.m_lines[y])) {
                this.m_changed = true;
            }
            if (newColourLines[y].equals(this.m_colourLines[y])) continue;
            this.m_changed = true;
        }
        this.m_lines = newLines;
        this.m_colourLines = newColourLines;
    }

    public synchronized void clear() {
        for (int y = 0; y < this.m_height; ++y) {
            if (!this.m_lines[y].equals(this.m_emptyLine)) {
                this.m_lines[y] = this.m_emptyLine;
                this.m_changed = true;
            }
            if (this.m_colourLines[y].equals(this.m_emptyColourLine)) continue;
            this.m_colourLines[y] = this.m_emptyColourLine;
            this.m_changed = true;
        }
    }

    public synchronized void clearLine() {
        if (this.m_cursorY >= 0 && this.m_cursorY < this.m_height) {
            if (!this.m_lines[this.m_cursorY].equals(this.m_emptyLine)) {
                this.m_lines[this.m_cursorY] = this.m_emptyLine;
                this.m_changed = true;
            }
            if (!this.m_colourLines[this.m_cursorY].equals(this.m_emptyColourLine)) {
                this.m_colourLines[this.m_cursorY] = this.m_emptyColourLine;
                this.m_changed = true;
            }
        }
    }

    public synchronized String getLine(int y) {
        if (y >= 0 && y < this.m_height) {
            return this.m_lines[y];
        }
        return this.m_emptyLine;
    }

    public synchronized void setLine(int y, String line, String colour) {
        this.m_lines[y] = (line + this.m_emptyLine).substring(0, this.m_width);
        this.m_colourLines[y] = (colour + this.m_emptyColourLine).substring(0, this.m_width * 2);
        this.m_changed = true;
    }

    public synchronized String getColourLine(int y) {
        if (y >= 0 && y < this.m_height) {
            return this.m_colourLines[y];
        }
        return "";
    }

    public synchronized boolean getChanged() {
        return this.m_changed;
    }

    public synchronized void clearChanged() {
        this.m_changed = false;
    }

    public synchronized void writeToNBT(NBTTagCompound nbttagcompound) {
        nbttagcompound.func_74768_a("term_cursorX", this.m_cursorX);
        nbttagcompound.func_74768_a("term_cursorY", this.m_cursorY);
        nbttagcompound.func_74757_a("term_cursorBlink", this.m_cursorBlink);
        nbttagcompound.func_74768_a("term_textColour", this.m_textColour);
        nbttagcompound.func_74768_a("term_bgColour", this.m_backgroundColour);
        for (int n = 0; n < this.m_height; ++n) {
            nbttagcompound.func_74778_a("term_line_" + n, this.m_lines[n]);
            nbttagcompound.func_74778_a("term_colourline_" + n, this.m_colourLines[n]);
        }
    }

    public synchronized void readFromNBT(NBTTagCompound nbttagcompound) {
        for (int n = 0; n < this.m_height; ++n) {
            this.m_lines[n] = nbttagcompound.func_74764_b("term_line_" + n) ? nbttagcompound.func_74779_i("term_line_" + n) : this.m_emptyLine;
            this.m_colourLines[n] = nbttagcompound.func_74764_b("term_colourline_" + n) ? nbttagcompound.func_74779_i("term_colourline_" + n) : this.m_emptyColourLine;
        }
        this.m_cursorX = nbttagcompound.func_74762_e("term_cursorX");
        this.m_cursorY = nbttagcompound.func_74762_e("term_cursorY");
        this.m_cursorBlink = nbttagcompound.func_74767_n("term_cursorBlink");
        this.m_textColour = nbttagcompound.func_74762_e("term_textColour");
        this.m_backgroundColour = nbttagcompound.func_74762_e("term_bgColour");
        this.rebuildEmptyColourLine();
        this.normaliseLines();
        this.m_changed = true;
    }
}
