/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  cpw.mods.fml.relauncher.Side
 *  cpw.mods.fml.relauncher.SideOnly
 *  dan200.computercraft.ComputerCraft$Blocks
 *  dan200.computercraft.api.peripheral.IComputerAccess
 *  dan200.computercraft.api.peripheral.IPeripheral
 *  dan200.computercraft.core.terminal.Terminal
 *  dan200.computercraft.shared.common.ClientTerminal
 *  dan200.computercraft.shared.common.ITerminal
 *  dan200.computercraft.shared.common.ITerminalTile
 *  dan200.computercraft.shared.common.ServerTerminal
 *  dan200.computercraft.shared.peripheral.PeripheralType
 *  dan200.computercraft.shared.peripheral.common.TilePeripheralBase
 *  dan200.computercraft.shared.peripheral.monitor.MonitorPeripheral
 *  net.minecraft.client.renderer.texture.IconRegister
 *  net.minecraft.entity.player.EntityPlayer
 *  net.minecraft.nbt.NBTTagCompound
 *  net.minecraft.tileentity.TileEntity
 *  net.minecraft.util.AxisAlignedBB
 *  net.minecraft.util.Facing
 *  net.minecraft.util.Icon
 */
package dan200.computercraft.shared.peripheral.monitor;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import dan200.computercraft.ComputerCraft;
import dan200.computercraft.api.peripheral.IComputerAccess;
import dan200.computercraft.api.peripheral.IPeripheral;
import dan200.computercraft.core.terminal.Terminal;
import dan200.computercraft.shared.common.ClientTerminal;
import dan200.computercraft.shared.common.ITerminal;
import dan200.computercraft.shared.common.ITerminalTile;
import dan200.computercraft.shared.common.ServerTerminal;
import dan200.computercraft.shared.peripheral.PeripheralType;
import dan200.computercraft.shared.peripheral.common.TilePeripheralBase;
import dan200.computercraft.shared.peripheral.monitor.MonitorPeripheral;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.client.renderer.texture.IconRegister;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.Facing;
import net.minecraft.util.Icon;

public class TileMonitor
extends TilePeripheralBase
implements ITerminalTile {
    public static final Icon[] s_icons = new Icon[48];
    public static final Icon[] s_advancedIcons = new Icon[48];
    public static final float RENDER_BORDER = 0.125f;
    public static final float RENDER_MARGIN = 0.03125f;
    public static final float RENDER_PIXEL_SCALE = 0.015625f;
    private static final int MAX_WIDTH = 8;
    private static final int MAX_HEIGHT = 6;
    private ServerTerminal m_serverTerminal;
    private ClientTerminal m_clientTerminal;
    private final Set<IComputerAccess> m_computers = new HashSet<IComputerAccess>();
    public int m_renderDisplayList = -1;
    private boolean m_destroyed = false;
    private boolean m_ignoreMe = false;
    private boolean m_changed = false;
    private int m_textScale = 2;
    private int m_width = 1;
    private int m_height = 1;
    private int m_xIndex = 0;
    private int m_yIndex = 0;
    private int m_dir = 2;
    private boolean m_sizeChangedQueued;

    @SideOnly(value=Side.CLIENT)
    public static void registerIcons(IconRegister iconRegister) {
        int i;
        for (i = 0; i <= 7; ++i) {
            TileMonitor.s_icons[i] = iconRegister.func_94245_a("computercraft:monitor" + i);
            TileMonitor.s_advancedIcons[i] = iconRegister.func_94245_a("computercraft:advMonitor" + i);
        }
        for (i = 15; i <= 47; ++i) {
            TileMonitor.s_icons[i] = iconRegister.func_94245_a("computercraft:monitor" + i);
            TileMonitor.s_advancedIcons[i] = iconRegister.func_94245_a("computercraft:advMonitor" + i);
        }
    }

    public static Icon getItemTexture(int side, boolean advanced) {
        Icon[] icons;
        Icon[] iconArray = icons = advanced ? s_advancedIcons : s_icons;
        if (side == 1 || side == 0) {
            return icons[0];
        }
        if (side == 3) {
            return icons[15];
        }
        return icons[32];
    }

    public TileMonitor() {
        super(s_icons);
    }

    public void destroy() {
        if (!this.m_destroyed) {
            this.m_destroyed = true;
            if (!this.field_70331_k.field_72995_K) {
                this.contractNeighbours();
            }
        }
    }

    public Icon getTexture(int side) {
        int right;
        int left;
        Icon[] texArray = this.getLocalTerminal().isColour() ? s_advancedIcons : s_icons;
        int xPos = this.getXIndex();
        int yPos = this.getYIndex();
        int width = this.getWidth();
        int height = this.getHeight();
        int dir = this.getRenderFace();
        int realDir = this.getDir();
        switch (realDir % 6) {
            default: {
                left = 4;
                right = 5;
                break;
            }
            case 3: {
                left = 5;
                right = 4;
                break;
            }
            case 4: {
                left = 3;
                right = 2;
                break;
            }
            case 5: {
                left = 2;
                right = 3;
            }
        }
        if (side == dir) {
            return realDir == 8 || realDir == 9 || realDir == 10 || realDir == 11 ? texArray[16 + this.getMonitorFaceTexture(width - 1 - xPos, yPos, width, height)] : texArray[16 + this.getMonitorFaceTexture(xPos, yPos, width, height)];
        }
        if (side == Facing.field_71588_a[dir]) {
            return realDir == 14 || realDir == 15 || realDir == 16 || realDir == 17 ? texArray[32 + this.getMonitorFaceTexture(xPos, yPos, width, height)] : texArray[32 + this.getMonitorFaceTexture(width - 1 - xPos, yPos, width, height)];
        }
        if (side == left || side == right) {
            if (height == 1) {
                return texArray[4];
            }
            if (yPos == 0) {
                return texArray[5];
            }
            if (yPos == height - 1) {
                return texArray[7];
            }
            return texArray[6];
        }
        if (width == 1) {
            return texArray[0];
        }
        if (xPos == 0) {
            return texArray[1];
        }
        if (xPos == width - 1) {
            return texArray[3];
        }
        return texArray[2];
    }

    public boolean onActivate(EntityPlayer player, int side, float hitX, float hitY, float hitZ) {
        if (!player.func_70093_af() && this.getRenderFace() == side) {
            if (!this.field_70331_k.field_72995_K) {
                this.monitorTouched(hitX, hitY, hitZ);
            }
            return true;
        }
        return false;
    }

    public void func_70310_b(NBTTagCompound nbttagcompound) {
        super.func_70310_b(nbttagcompound);
        nbttagcompound.func_74768_a("xIndex", this.m_xIndex);
        nbttagcompound.func_74768_a("yIndex", this.m_yIndex);
        nbttagcompound.func_74768_a("width", this.m_width);
        nbttagcompound.func_74768_a("height", this.m_height);
        nbttagcompound.func_74768_a("dir", this.m_dir);
    }

    public void func_70307_a(NBTTagCompound nbttagcompound) {
        super.func_70307_a(nbttagcompound);
        this.m_xIndex = nbttagcompound.func_74762_e("xIndex");
        this.m_yIndex = nbttagcompound.func_74762_e("yIndex");
        this.m_width = nbttagcompound.func_74762_e("width");
        this.m_height = nbttagcompound.func_74762_e("height");
        this.m_dir = nbttagcompound.func_74762_e("dir");
    }

    public void func_70316_g() {
        if (!this.field_70331_k.field_72995_K) {
            if (this.m_sizeChangedQueued) {
                for (IComputerAccess computer : this.m_computers) {
                    computer.queueEvent("monitor_resize", new Object[]{computer.getAttachmentName()});
                }
                this.m_sizeChangedQueued = false;
            }
            if (this.m_serverTerminal != null) {
                this.m_serverTerminal.update();
                if (this.m_serverTerminal.hasTerminalChanged()) {
                    this.updateBlock();
                }
            }
            if (this.m_clientTerminal != null) {
                this.m_clientTerminal.update();
            }
        }
    }

    public boolean pollChanged() {
        if (this.m_changed) {
            this.m_changed = false;
            return true;
        }
        return false;
    }

    public IPeripheral getPeripheral(int side) {
        return new MonitorPeripheral(this);
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    public void setTextScale(int scale) {
        TileMonitor origin;
        TileMonitor tileMonitor = origin = this.getOrigin();
        synchronized (tileMonitor) {
            if (origin.m_textScale != scale) {
                origin.m_textScale = scale;
                origin.rebuildTerminal();
                origin.updateBlock();
            }
        }
    }

    public void writeDescription(NBTTagCompound nbttagcompound) {
        super.writeDescription(nbttagcompound);
        nbttagcompound.func_74768_a("xIndex", this.m_xIndex);
        nbttagcompound.func_74768_a("yIndex", this.m_yIndex);
        nbttagcompound.func_74768_a("width", this.m_width);
        nbttagcompound.func_74768_a("height", this.m_height);
        nbttagcompound.func_74768_a("textScale", this.m_textScale);
        nbttagcompound.func_74768_a("monitorDir", this.m_dir);
        ((ServerTerminal)this.getLocalTerminal()).writeDescription(nbttagcompound);
    }

    public final void readDescription(NBTTagCompound nbttagcompound) {
        super.readDescription(nbttagcompound);
        int oldXIndex = this.m_xIndex;
        int oldYIndex = this.m_yIndex;
        int oldWidth = this.m_width;
        int oldHeight = this.m_height;
        int oldTextScale = this.m_textScale;
        int oldDir = this.m_dir;
        this.m_xIndex = nbttagcompound.func_74762_e("xIndex");
        this.m_yIndex = nbttagcompound.func_74762_e("yIndex");
        this.m_width = nbttagcompound.func_74762_e("width");
        this.m_height = nbttagcompound.func_74762_e("height");
        this.m_textScale = nbttagcompound.func_74762_e("textScale");
        this.m_dir = nbttagcompound.func_74762_e("monitorDir");
        ((ClientTerminal)this.getLocalTerminal()).readDescription(nbttagcompound);
        this.m_changed = true;
        if (oldXIndex != this.m_xIndex || oldYIndex != this.m_yIndex || oldWidth != this.m_width || oldHeight != this.m_height || oldTextScale != this.m_textScale || oldDir != this.m_dir) {
            this.updateBlock();
        }
    }

    public ITerminal getTerminal() {
        TileMonitor origin = this.getOrigin();
        return origin.getLocalTerminal();
    }

    private ITerminal getLocalTerminal() {
        if (!this.field_70331_k.field_72995_K) {
            if (this.m_serverTerminal == null) {
                this.m_serverTerminal = new ServerTerminal(this.getPeripheralType() == PeripheralType.AdvancedMonitor);
            }
            return this.m_serverTerminal;
        }
        if (this.m_clientTerminal == null) {
            this.m_clientTerminal = new ClientTerminal(this.getPeripheralType() == PeripheralType.AdvancedMonitor);
        }
        return this.m_clientTerminal;
    }

    public float getTextScale() {
        return (float)this.m_textScale * 0.5f;
    }

    private void rebuildTerminal() {
        Terminal oldTerm = this.getTerminal().getTerminal();
        int oldWidth = oldTerm != null ? oldTerm.getWidth() : -1;
        int oldHeight = oldTerm != null ? oldTerm.getHeight() : -1;
        float textScale = this.getTextScale();
        int termWidth = Math.max(Math.round(((float)this.m_width - 0.3125f) / (textScale * 6.0f * 0.015625f)), 1);
        int termHeight = Math.max(Math.round(((float)this.m_height - 0.3125f) / (textScale * 9.0f * 0.015625f)), 1);
        ((ServerTerminal)this.getLocalTerminal()).resize(termWidth, termHeight);
        if (oldWidth != termWidth || oldHeight != termHeight) {
            this.getLocalTerminal().getTerminal().clear();
            for (int y = 0; y < this.m_height; ++y) {
                for (int x = 0; x < this.m_width; ++x) {
                    TileMonitor monitor = this.getNeighbour(x, y);
                    if (monitor == null) continue;
                    monitor.queueSizeChangedEvent();
                }
            }
        }
    }

    private void destroyTerminal() {
        ((ServerTerminal)this.getLocalTerminal()).delete();
    }

    public int getRenderFace() {
        return this.m_dir <= 5 ? this.m_dir : (this.m_dir <= 11 ? 0 : 1);
    }

    public int getDir() {
        return this.m_dir;
    }

    public void setDir(int dir) {
        this.m_dir = dir;
        this.m_changed = true;
        this.func_70296_d();
    }

    public int getRight() {
        int dir = this.getDir() % 6;
        switch (dir) {
            case 2: {
                return 4;
            }
            case 3: {
                return 5;
            }
            case 4: {
                return 3;
            }
            case 5: {
                return 2;
            }
        }
        return dir;
    }

    private int getDown() {
        int dir = this.getDir();
        if (dir <= 5) {
            return 1;
        }
        switch (dir) {
            case 8: {
                return 2;
            }
            case 9: {
                return 3;
            }
            case 10: {
                return 4;
            }
            case 11: {
                return 5;
            }
            case 14: {
                return 3;
            }
            case 15: {
                return 2;
            }
            case 16: {
                return 5;
            }
            case 17: {
                return 4;
            }
        }
        return dir;
    }

    public int getWidth() {
        return this.m_width;
    }

    public int getHeight() {
        return this.m_height;
    }

    public int getXIndex() {
        return this.m_xIndex;
    }

    public int getYIndex() {
        return this.m_yIndex;
    }

    private TileMonitor getSimilarMonitorAt(int x, int y, int z) {
        TileMonitor monitor;
        TileEntity tile;
        if (y >= 0 && y < this.field_70331_k.func_72800_K() && this.field_70331_k.func_72899_e(x, y, z) && (tile = this.field_70331_k.func_72796_p(x, y, z)) != null && tile instanceof TileMonitor && (monitor = (TileMonitor)tile).getDir() == this.getDir() && monitor.getLocalTerminal().isColour() == this.getLocalTerminal().isColour() && !monitor.m_destroyed && !monitor.m_ignoreMe) {
            return monitor;
        }
        return null;
    }

    private TileMonitor getNeighbour(int x, int y) {
        int right = this.getRight();
        int down = this.getDown();
        int xOffset = -this.m_xIndex + x;
        int yOffset = -this.m_yIndex + y;
        return this.getSimilarMonitorAt(this.field_70329_l + Facing.field_71586_b[right] * xOffset + Facing.field_71586_b[down] * yOffset, this.field_70330_m + Facing.field_71587_c[right] * xOffset + Facing.field_71587_c[down] * yOffset, this.field_70327_n + Facing.field_71585_d[right] * xOffset + Facing.field_71585_d[down] * yOffset);
    }

    private TileMonitor getOrigin() {
        return this.getNeighbour(0, 0);
    }

    private void resize(int width, int height) {
        int right = this.getRight();
        int rightX = Facing.field_71586_b[right];
        int rightY = Facing.field_71587_c[right];
        int rightZ = Facing.field_71585_d[right];
        int down = this.getDown();
        int downX = Facing.field_71586_b[down];
        int downY = Facing.field_71587_c[down];
        int downZ = Facing.field_71585_d[down];
        for (int y = 0; y < height; ++y) {
            for (int x = 0; x < width; ++x) {
                TileMonitor monitor = this.getSimilarMonitorAt(this.field_70329_l + rightX * x + downX * y, this.field_70330_m + rightY * x + downY * y, this.field_70327_n + rightZ * x + downZ * y);
                if (monitor == null) continue;
                monitor.m_xIndex = x;
                monitor.m_yIndex = y;
                monitor.m_width = width;
                monitor.m_height = height;
                monitor.updateBlock();
                if (x == 0 && y == 0) continue;
                monitor.destroyTerminal();
            }
        }
        this.rebuildTerminal();
    }

    private boolean mergeLeft() {
        int width;
        TileMonitor left = this.getNeighbour(-1, 0);
        if (left != null && left.m_yIndex == 0 && left.m_height == this.m_height && (width = left.m_width + this.m_width) <= 8) {
            left.getOrigin().resize(width, this.m_height);
            left.expand();
            return true;
        }
        return false;
    }

    private boolean mergeRight() {
        int width;
        TileMonitor right = this.getNeighbour(this.m_width, 0);
        if (right != null && right.m_yIndex == 0 && right.m_height == this.m_height && (width = this.m_width + right.m_width) <= 8) {
            this.getOrigin().resize(width, this.m_height);
            this.expand();
            return true;
        }
        return false;
    }

    private boolean mergeUp() {
        int height;
        TileMonitor above = this.getNeighbour(0, this.m_height);
        if (above != null && above.m_xIndex == 0 && above.m_width == this.m_width && (height = above.m_height + this.m_height) <= 6) {
            this.getOrigin().resize(this.m_width, height);
            this.expand();
            return true;
        }
        return false;
    }

    private boolean mergeDown() {
        int height;
        TileMonitor below = this.getNeighbour(0, -1);
        if (below != null && below.m_xIndex == 0 && below.m_width == this.m_width && (height = this.m_height + below.m_height) <= 6) {
            below.getOrigin().resize(this.m_width, height);
            below.expand();
            return true;
        }
        return false;
    }

    public void expand() {
        while (this.mergeLeft() || this.mergeRight() || this.mergeUp() || this.mergeDown()) {
        }
    }

    public void contractNeighbours() {
        TileMonitor above;
        TileMonitor below;
        TileMonitor right;
        TileMonitor left;
        this.m_ignoreMe = true;
        if (this.m_xIndex > 0 && (left = this.getNeighbour(this.m_xIndex - 1, this.m_yIndex)) != null) {
            left.contract();
        }
        if (this.m_xIndex + 1 < this.m_width && (right = this.getNeighbour(this.m_xIndex + 1, this.m_yIndex)) != null) {
            right.contract();
        }
        if (this.m_yIndex > 0 && (below = this.getNeighbour(this.m_xIndex, this.m_yIndex - 1)) != null) {
            below.contract();
        }
        if (this.m_yIndex + 1 < this.m_height && (above = this.getNeighbour(this.m_xIndex, this.m_yIndex + 1)) != null) {
            above.contract();
        }
        this.m_ignoreMe = false;
    }

    public void contract() {
        int height = this.m_height;
        int width = this.m_width;
        TileMonitor origin = this.getOrigin();
        if (origin == null) {
            TileMonitor right = null;
            TileMonitor below = null;
            if (width > 1) {
                right = this.getNeighbour(1, 0);
            }
            if (height > 1) {
                below = this.getNeighbour(0, 1);
            }
            if (right != null) {
                right.resize(width - 1, 1);
            }
            if (below != null) {
                below.resize(width, height - 1);
            }
            if (right != null) {
                right.expand();
            }
            if (below != null) {
                below.expand();
            }
            return;
        }
        for (int y = 0; y < height; ++y) {
            for (int x = 0; x < width; ++x) {
                TileMonitor monitor = origin.getNeighbour(x, y);
                if (monitor != null) continue;
                TileMonitor above = null;
                TileMonitor left = null;
                TileMonitor right = null;
                TileMonitor below = null;
                if (y > 0) {
                    above = origin;
                    above.resize(width, y);
                }
                if (x > 0) {
                    left = origin.getNeighbour(0, y);
                    left.resize(x, 1);
                }
                if (x + 1 < width) {
                    right = origin.getNeighbour(x + 1, y);
                    right.resize(width - (x + 1), 1);
                }
                if (y + 1 < height) {
                    below = origin.getNeighbour(0, y + 1);
                    below.resize(width, height - (y + 1));
                }
                if (above != null) {
                    above.expand();
                }
                if (left != null) {
                    left.expand();
                }
                if (right != null) {
                    right.expand();
                }
                if (below != null) {
                    below.expand();
                }
                return;
            }
        }
    }

    public void monitorTouched(float xPos, float yPos, float zPos) {
        int side = this.getDir();
        XYPair pair = this.convertToXY(xPos, yPos, zPos, side);
        pair = new XYPair(pair.x + (float)this.m_xIndex, pair.y + (float)this.m_height - (float)this.m_yIndex - 1.0f);
        if (pair.x > (float)this.m_width - 0.125f || pair.y > (float)this.m_height - 0.125f || pair.x < 0.125f || pair.y < 0.125f) {
            return;
        }
        Terminal originTerminal = this.getTerminal().getTerminal();
        if (originTerminal == null) {
            return;
        }
        if (!this.getTerminal().isColour()) {
            return;
        }
        float xCharWidth = ((float)this.m_width - 0.3125f) / (float)originTerminal.getWidth();
        float yCharHeight = ((float)this.m_height - 0.3125f) / (float)originTerminal.getHeight();
        int xCharPos = (int)Math.min((float)originTerminal.getWidth(), Math.max((pair.x - 0.125f - 0.03125f) / xCharWidth + 1.0f, 1.0f));
        int yCharPos = (int)Math.min((float)originTerminal.getHeight(), Math.max((pair.y - 0.125f - 0.03125f) / yCharHeight + 1.0f, 1.0f));
        for (int y = 0; y < this.m_height; ++y) {
            for (int x = 0; x < this.m_width; ++x) {
                TileMonitor monitor = this.getNeighbour(x, y);
                if (monitor == null) continue;
                monitor.queueTouchEvent(xCharPos, yCharPos);
            }
        }
    }

    private void queueTouchEvent(int xCharPos, int yCharPos) {
        for (IComputerAccess computer : this.m_computers) {
            computer.queueEvent("monitor_touch", new Object[]{computer.getAttachmentName(), xCharPos, yCharPos});
        }
    }

    private void queueSizeChangedEvent() {
        this.m_sizeChangedQueued = true;
    }

    private XYPair convertToXY(float xPos, float yPos, float zPos, int side) {
        switch (side) {
            case 2: {
                return new XYPair(1.0f - xPos, 1.0f - yPos);
            }
            case 3: {
                return new XYPair(xPos, 1.0f - yPos);
            }
            case 4: {
                return new XYPair(zPos, 1.0f - yPos);
            }
            case 5: {
                return new XYPair(1.0f - zPos, 1.0f - yPos);
            }
            case 8: {
                return new XYPair(1.0f - xPos, zPos);
            }
            case 9: {
                return new XYPair(xPos, 1.0f - zPos);
            }
            case 10: {
                return new XYPair(zPos, xPos);
            }
            case 11: {
                return new XYPair(1.0f - zPos, 1.0f - xPos);
            }
            case 14: {
                return new XYPair(1.0f - xPos, 1.0f - zPos);
            }
            case 15: {
                return new XYPair(xPos, zPos);
            }
            case 16: {
                return new XYPair(zPos, 1.0f - xPos);
            }
            case 17: {
                return new XYPair(1.0f - zPos, xPos);
            }
        }
        return new XYPair(xPos, zPos);
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    public void addComputer(IComputerAccess computer) {
        TileMonitor tileMonitor = this;
        synchronized (tileMonitor) {
            if (this.m_computers.size() == 0) {
                this.getOrigin().rebuildTerminal();
            }
            if (!this.m_computers.contains(computer)) {
                this.m_computers.add(computer);
            }
        }
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    public void removeComputer(IComputerAccess computer) {
        TileMonitor tileMonitor = this;
        synchronized (tileMonitor) {
            if (this.m_computers.contains(computer)) {
                this.m_computers.remove(computer);
            }
        }
    }

    public AxisAlignedBB getRenderBoundingBox() {
        if (this.getXIndex() != 0 || this.getYIndex() != 0) {
            return ComputerCraft.Blocks.peripheral.func_71872_e(this.field_70331_k, this.field_70329_l, this.field_70330_m, this.field_70327_n);
        }
        TileMonitor monitor = this.getNeighbour(this.m_width - 1, this.m_height - 1);
        if (monitor != null) {
            int minX = Math.min(this.field_70329_l, monitor.field_70329_l);
            int minY = Math.min(this.field_70330_m, monitor.field_70330_m);
            int minZ = Math.min(this.field_70327_n, monitor.field_70327_n);
            int maxX = (minX == monitor.field_70329_l ? this.field_70329_l : monitor.field_70329_l) + 1;
            int maxY = (minY == monitor.field_70330_m ? this.field_70330_m : monitor.field_70330_m) + 1;
            int maxZ = (minZ == monitor.field_70327_n ? this.field_70327_n : monitor.field_70327_n) + 1;
            return AxisAlignedBB.func_72332_a().func_72299_a((double)minX, (double)minY, (double)minZ, (double)maxX, (double)maxY, (double)maxZ);
        }
        return ComputerCraft.Blocks.peripheral.func_71872_e(this.field_70331_k, this.field_70329_l, this.field_70330_m, this.field_70327_n);
    }

    private int getMonitorFaceTexture(int xPos, int yPos, int width, int height) {
        if (width == 1 && height == 1) {
            return 0;
        }
        if (height == 1) {
            if (xPos == 0) {
                return 1;
            }
            if (xPos == width - 1) {
                return 3;
            }
            return 2;
        }
        if (width == 1) {
            if (yPos == 0) {
                return 6;
            }
            if (yPos == height - 1) {
                return 4;
            }
            return 5;
        }
        if (yPos == 0) {
            if (xPos == 0) {
                return 7;
            }
            if (xPos == width - 1) {
                return 9;
            }
            return 8;
        }
        if (yPos == height - 1) {
            if (xPos == 0) {
                return 13;
            }
            if (xPos == width - 1) {
                return 15;
            }
            return 14;
        }
        if (xPos == 0) {
            return 10;
        }
        if (xPos == width - 1) {
            return 12;
        }
        return 11;
    }

    public static class XYPair {
        public final float x;
        public final float y;

        private XYPair(float x, float y) {
            this.x = x;
            this.y = y;
        }
    }
}
