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
        ChessboardBlock.openScreenAction.accept(blockHit.getBlockPos());
        cir.setReturnValue(InteractionResult.FAIL);
    }
}
