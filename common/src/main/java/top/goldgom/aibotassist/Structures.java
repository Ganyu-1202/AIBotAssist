package top.goldgom.aibotassist;

import java.util.*;

public final class Structures {
    private Structures() {}
    public static Plan create(String kind,int width,int depth,int height,String material,String facing,int ox,int oy,int oz) {
        return create(kind,width,depth,height,material,Map.of(),facing,ox,oy,oz);
    }
    public static Plan create(String kind,int width,int depth,int height,String material,Map<String,String> palette,String facing,int ox,int oy,int oz) {
        if(!palette.keySet().stream().allMatch(Set.of("wall","floor","roof","frame","window")::contains)||!palette.values().stream().allMatch(Plan::validBlockId))throw new IllegalArgumentException("调色板材料无效");
        if(width<3||width>11||depth<3||depth>11||height<2||height>8||!Plan.validBlockId(material))throw new IllegalArgumentException("尺寸或材料无效");
        Map<String,Plan.Placement> map=new LinkedHashMap<>();
        for(int x=0;x<width;x++)for(int z=0;z<depth;z++)for(int y=0;y<=height;y++) {
            boolean place=switch(kind) {
                case "house" -> y==0||y==height||((x==0||x==width-1||z==0||z==depth-1)&&!(z==0&&x==width/2&&(y==1||y==2)));
                case "wall" -> z==0&&y<height;
                case "bridge" -> y==0||((x==0||x==width-1)&&y==1);
                case "stairs" -> z<height&&y==z;
                default -> throw new IllegalArgumentException("结构类型支持house/wall/bridge/stairs");
            };
            if(!place)continue;
            int a=x-width/2,b=z+3,rx,rz;
            switch(facing) {case "north"->{rx=a;rz=-b;}case "east"->{rx=b;rz=a;}case "west"->{rx=-b;rz=a;}case "south"->{rx=a;rz=b;}default->throw new IllegalArgumentException("无效朝向");}
            rx+=ox;rz+=oz;int ry=y+oy;
            if(Math.abs(rx)>16||Math.abs(ry)>16||Math.abs(rz)>16)throw new IllegalArgumentException("结构超出±16格范围");
            String role="wall";
            if(kind.equals("house")) {
                if(y==0)role="floor";
                else if(y==height)role="roof";
                else if((x==0||x==width-1)&&(z==0||z==depth-1))role="frame";
                else if(y==2 && ((z==0||z==depth-1)&&x>0&&x<width-1|| (x==0||x==width-1)&&z>0&&z<depth-1))role="window";
            } else if(kind.equals("bridge"))role=y==0?"floor":"frame";
            String chosen=palette.getOrDefault(role,material);
            var block=new Plan.Placement(rx,ry,rz,chosen);map.put(rx+","+ry+","+rz,block);
        }
        if(map.size()>Plan.MAX_BLOCKS)throw new IllegalArgumentException("结构超过256格，请缩小尺寸");
        return new Plan("生成"+kind,List.copyOf(map.values()));
    }
}
