package top.kdla.framework.llm.mentor.rag.agent.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

/**
 * 天气查询工具（示例工具，演示 Agent 多工具协作）
 */
@Slf4j
@Service
public class WeatherTool {

    @Tool(name = "getWeather", description = "根据城市名称查询当前天气信息", returnDirect = true)
    public String getWeather(@ToolParam(description = "城市名称，例如：北京、上海、深圳") String city) {
        log.info("WeatherTool.getWeather called for city: {}", city);
        if (city == null || city.isBlank()) {
            return "请提供城市名称";
        }
        return switch (city.trim()) {
            case "北京"  -> "北京：晴，气温 5°C，东北风 3 级";
            case "上海"  -> "上海：多云，气温 12°C，东南风 2 级";
            case "深圳"  -> "深圳：小雨，气温 28°C，南风 1 级";
            case "广州"  -> "广州：阴，气温 25°C，西南风 2 级";
            case "杭州"  -> "杭州：晴，气温 10°C，东风 2 级";
            default     -> city + "：天气数据暂不可用，请稍后再试";
        };
    }
}
