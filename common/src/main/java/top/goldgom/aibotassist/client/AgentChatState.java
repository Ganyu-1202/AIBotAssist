package top.goldgom.aibotassist.client;

import java.util.*;

/** Save/player-local conversation and transient work status. */
public final class AgentChatState {
    public enum Role { USER, ASSISTANT, NOTICE }
    public enum Phase { IDLE, PREPARING, THINKING, TOOL, WAITING, COMPLETED, FAILED, CANCELLED }
    public record Message(Role role,String text) {}
    public record Progress(Phase phase,String tool,int modelTurns,int toolCalls) {}
    public record Work(Phase phase,String tool,int modelTurns,int toolCalls,long started,long updated) {
        public boolean active(){return switch(phase){case PREPARING,THINKING,TOOL,WAITING->true;default->false;};}
    }
    private final Deque<Message> messages=new ArrayDeque<>();
    private Work work=new Work(Phase.IDLE,"",0,0,0,0);
    private long revision;
    private String draft="";
    public synchronized void append(Role role,String text) {
        if(text==null || text.isBlank())return;
        messages.addLast(new Message(role,text.substring(0,Math.min(6000,text.length()))));
        while(messages.size()>160)messages.removeFirst();revision++;
    }
    public synchronized List<Message> messages(){return List.copyOf(messages);}
    public synchronized long revision(){return revision;}
    public synchronized Work work(){return work;}
    public synchronized void begin(){long now=System.currentTimeMillis();work=new Work(Phase.PREPARING,"",0,0,now,now);}
    public synchronized void progress(Progress progress) {
        if(!work.active())return;
        work=new Work(progress.phase(),progress.tool(),progress.modelTurns(),progress.toolCalls(),work.started(),System.currentTimeMillis());
    }
    public synchronized void finish(Phase phase) {
        if(phase!=Phase.COMPLETED && phase!=Phase.FAILED && phase!=Phase.CANCELLED)throw new IllegalArgumentException("Expected terminal phase");
        work=new Work(phase,work.tool(),work.modelTurns(),work.toolCalls(),work.started(),System.currentTimeMillis());
    }
    public synchronized String draft(){return draft;}
    public synchronized void draft(String value){draft=value.substring(0,Math.min(2000,value.length()));}
    public synchronized void clearMessages(){messages.clear();revision++;}
    public synchronized void reset(){clearMessages();draft="";work=new Work(Phase.IDLE,"",0,0,0,0);}
    public synchronized void restore(List<Message> history,String draft){
        reset();for(var message:history)append(message.role(),message.text());draft(draft);
    }
    public synchronized String promptWithHistory(String prompt) {
        List<Message> recent=messages.stream().filter(m->m.role()!=Role.NOTICE).toList();
        if(recent.isEmpty())return prompt;
        StringBuilder context=new StringBuilder("以下是当前世界中最近的对话，仅作为对话背景。重新观察当前世界，不复用旧token，不假定先前操作仍有效。\n");
        for(Message message:recent.subList(Math.max(0,recent.size()-8),recent.size()))
            context.append(message.role()==Role.USER?"玩家：":"助手：").append(message.text(),0,Math.min(1500,message.text().length())).append('\n');
        return context.append("\n当前玩家请求：\n").append(prompt).toString();
    }
}
