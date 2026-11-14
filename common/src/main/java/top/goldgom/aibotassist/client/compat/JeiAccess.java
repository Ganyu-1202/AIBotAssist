package top.goldgom.aibotassist.client.compat;

import com.google.gson.*;
import java.util.*;
import mezz.jei.api.*;
import mezz.jei.api.runtime.IJeiRuntime;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.*;
import mezz.jei.api.recipe.category.IRecipeCategory;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import top.goldgom.aibotassist.AdvancedTools;

/** Loaded by JEI only. No reference from common bootstrap, no mandatory runtime dependency. */
@JeiPlugin
public final class JeiAccess implements IModPlugin {
    private static IJeiRuntime runtime;
    public ResourceLocation getPluginUid(){return ResourceLocation.fromNamespaceAndPath("aibotassist","recipes");}
    public void onRuntimeAvailable(IJeiRuntime value){runtime=value;}
    public void onRuntimeUnavailable(){runtime=null;}
    public static JsonObject query(JsonObject args){
        if(runtime==null)return AdvancedTools.error("JEI尚未就绪；等待进入世界后重试或使用服务器query_recipes");
        int offset=integer(args,"offset",0,0,Integer.MAX_VALUE),limit=integer(args,"limit",4,1,8);
        var manager=runtime.getRecipeManager();var factory=runtime.getJeiHelpers().getFocusFactory();List<IFocus<?>> focuses=new ArrayList<>();
        if(args.has("item")){var id=ResourceLocation.parse(args.get("item").getAsString());if(!BuiltInRegistries.ITEM.containsKey(id))return AdvancedTools.error("unknown_item");
            RecipeIngredientRole role=args.has("role")&&args.get("role").getAsString().equals("input")?RecipeIngredientRole.INPUT:RecipeIngredientRole.OUTPUT;
            focuses.add(factory.createFocus(role,VanillaTypes.ITEM_STACK,new ItemStack(BuiltInRegistries.ITEM.get(id))));}
        var categories=manager.createRecipeCategoryLookup().limitFocus(focuses).get().toList();JsonObject o=AdvancedTools.ok();JsonArray rows=new JsonArray();
        if(!args.has("item")&&!args.has("category")){
            for(var c:categories.stream().skip(offset).limit(40).toList()){JsonObject v=new JsonObject();v.addProperty("category",c.getRecipeType().getUid().toString());v.addProperty("title",c.getTitle().getString());rows.add(v);}o.add("categories",rows);o.addProperty("next_offset",offset+rows.size());o.addProperty("total",categories.size());return o;
        }
        int matched=0,errors=0;boolean more=false;
        outer:for(var c:categories){
            if(args.has("category")&&!c.getRecipeType().getUid().toString().equals(args.get("category").getAsString()))continue;
            try(var recipes=manager.createRecipeLookup(c.getRecipeType()).limitFocus(focuses).get()){
                var iterator=recipes.iterator();while(iterator.hasNext()){
                    Object recipe=iterator.next();if(matched++<offset)continue;if(rows.size()>=limit){more=true;break outer;}
                    try{rows.add(describe(c,recipe));}catch(RuntimeException error){errors++;}
                }
            }
        }
        o.add("recipes",rows);o.addProperty("next_offset",offset+rows.size()+errors);o.addProperty("truncated",more);o.addProperty("unsupported_recipes",errors);
        o.addProperty("note","JEI显示信息，非自动化能力保证。槽位保留替代材料和数量；能量/耗时等仅画在界面的信息不一定包含。先确认机器I/O与侧面配置再建造，严禁凭空编造工业配方。");return o;
    }
    @SuppressWarnings({"unchecked","rawtypes"}) private static JsonObject describe(IRecipeCategory category,Object recipe){
        var manager=runtime.getRecipeManager();JsonObject row=new JsonObject();row.addProperty("category",category.getRecipeType().getUid().toString());row.addProperty("title",category.getTitle().getString());
        ResourceLocation id=category.getRegistryName(recipe);if(id!=null)row.addProperty("recipe_id",id.toString());
        JsonArray catalysts=new JsonArray();manager.createRecipeCatalystLookup(category.getRecipeType()).get().limit(8).forEach(v->catalysts.add(ingredient(v)));row.add("machines",catalysts);
        var layout=manager.createRecipeLayoutDrawable(category,recipe,runtime.getJeiHelpers().getFocusFactory().getEmptyFocusGroup());
        if(layout.isEmpty()){row.addProperty("layout_unavailable",true);return row;}
        var drawable=(mezz.jei.api.gui.IRecipeLayoutDrawable<?>)layout.get();var views=drawable.getRecipeSlotsView().getSlotViews();JsonArray slots=new JsonArray();
        for(var slot:views.stream().limit(32).toList()){
            JsonObject s=new JsonObject();s.addProperty("role",slot.getRole().name());slot.getSlotName().ifPresent(n->s.addProperty("name",n));
            var alternatives=slot.getAllIngredients().limit(9).toList();JsonArray values=new JsonArray();alternatives.stream().limit(8).forEach(v->values.add(ingredient(v)));s.add("alternatives",values);s.addProperty("truncated",alternatives.size()>8);slots.add(s);
        }
        row.add("slots",slots);row.addProperty("slots_truncated",views.size()>32);return row;
    }
    private static <T> JsonObject ingredient(ITypedIngredient<T> typed){
        var helper=runtime.getIngredientManager().getIngredientHelper(typed.getType());T value=typed.getIngredient();JsonObject o=new JsonObject();o.addProperty("id",helper.getResourceLocation(value).toString());o.addProperty("name",helper.getDisplayName(value));o.addProperty("amount",helper.getAmount(value));o.addProperty("kind",typed.getType().getIngredientClass().getSimpleName());
        if(value instanceof ItemStack stack){o.addProperty("amount",stack.getCount());o.addProperty("has_components",!stack.getComponentsPatch().isEmpty());}return o;
    }
    private static int integer(JsonObject a,String key,int fallback,int min,int max){int v=a.has(key)?a.get(key).getAsBigDecimal().intValueExact():fallback;if(v<min||v>max)throw new IllegalArgumentException();return v;}
}
