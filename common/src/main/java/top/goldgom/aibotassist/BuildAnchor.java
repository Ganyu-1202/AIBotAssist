package top.goldgom.aibotassist;

import net.minecraft.core.BlockPos;

/** One fixed reference frame for every observe/execute cycle in a player request. */
public record BuildAnchor(String session,BlockPos origin,String dimension,String facing,int blockLimit,long expires) {
    public BuildAnchor {
        if(session==null||!session.matches("[a-zA-Z0-9-]{1,64}"))throw new IllegalArgumentException("Invalid build session");
        Plan.checkLimit(blockLimit,0);origin=origin.immutable();
    }
    public BuildAnchor reuse(String requestedSession,String currentDimension,int requestedLimit,long now) {
        if(!session.equals(requestedSession)||expires<now||!dimension.equals(currentDimension))throw new IllegalArgumentException("建造会话失效，请开始新的请求");
        if(blockLimit!=requestedLimit)throw new IllegalArgumentException("建造途中限制改变，请开始新的请求");
        return this;
    }
}
