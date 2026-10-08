package com.chessboard.mixin;

import com.chessboard.ChessboardClient;
import com.chessboard.Config;
import com.chessboard.block.ChessboardBlock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MultiPlayerGameMode.class)
public class MultiPlayerGameModeMixin {

    @Inject(method = "useItemOn", at = @At("HEAD"), cancellable = true)
    private void onUseItemOn(net.minecraft.client.player.LocalPlayer player, InteractionHand hand,
                             BlockHitResult blockHit, CallbackInfoReturnable<InteractionResult> cir) {
        boolean menuKey = ChessboardClient.OPEN_MENU.isDown();
        if (!menuKey && !Config.RIGHT_CLICK_OPENS_MENU.get()) return;
        Level level = Minecraft.getInstance().level;
        if (level == null) return;
        BlockState state = level.getBlockState(blockHit.getBlockPos());
        if (!(state.getBlock() instanceof ChessboardBlock)) return;
        // 未按住菜单键时，只有点击侧面才开菜单，上下面继续落子
        if (!menuKey && blockHit.getDirection() == Direction.UP) return;
        if (!menuKey && blockHit.getDirection() == Direction.DOWN) return;
        // 骰子格上那块小突起也是竖直的方块，它的「侧面」同样是南北东西 —— 但那不是棋盘侧面，
        // 是骰子。不排掉的话，右键骰子会弹皮肤菜单而不是掷骰子（2026-10-08）。
        if (!menuKey && ChessboardBlock.hitsDiceBump(blockHit.getBlockPos(), blockHit)) return;
        // 菜单是服务端权威的，这里只能发请求（服务端校验后会自己把界面推回来）
        ChessboardClient.requestBoardScreen(blockHit.getBlockPos());
        cir.setReturnValue(InteractionResult.FAIL);
    }
}
