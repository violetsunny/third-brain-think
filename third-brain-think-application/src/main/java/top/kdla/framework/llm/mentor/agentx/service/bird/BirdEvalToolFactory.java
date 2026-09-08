package top.kdla.framework.llm.mentor.agentx.service.bird;

import top.kdla.framework.llm.mentor.agentx.service.bird.sqlite.SqliteSchemaProvider;
import top.kdla.framework.llm.mentor.agentx.service.bird.sqlite.SqliteQueryExecutor;
import top.kdla.framework.llm.mentor.agentx.tools.DescribeTablesTool;
import top.kdla.framework.llm.mentor.agentx.tools.ListTablesTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

/**
 * BIRD evaluation tool factory.
 *
 * <p>The bundle intentionally contains schema inspection and one dynamic SQL
 * verifier. Production SQL safety checks, permission rewrites, and result
 * truncation remain isolated in the regular data-agent.</p>
 */
@Service
public class BirdEvalToolFactory {

    private final ObjectMapper objectMapper;

    public BirdEvalToolFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public BirdToolBundle create(String sqlitePath, String reviewContext) {
        SqliteSchemaProvider schemaProvider = new SqliteSchemaProvider(sqlitePath);
        SqliteQueryExecutor executor = new SqliteQueryExecutor(sqlitePath);

        return new BirdToolBundle(
                new ListTablesTool(schemaProvider),
                new DescribeTablesTool(schemaProvider),
                new BirdVerifySqlTool(sqlitePath, executor, reviewContext, objectMapper)
        );
    }

    public String describeAllTables(String sqlitePath) {
        return new SqliteSchemaProvider(sqlitePath).describeAllTables();
    }

    public record BirdToolBundle(
            ListTablesTool listTablesTool,
            DescribeTablesTool describeTablesTool,
            BirdVerifySqlTool verifySqlTool
    ) {
    }
}
