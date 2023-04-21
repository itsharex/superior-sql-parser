package io.github.melin.superior.parser.mysql

import com.github.melin.superior.sql.parser.util.StringUtil
import io.github.melin.superior.common.*
import io.github.melin.superior.common.relational.*
import io.github.melin.superior.common.relational.create.CreateIndex
import io.github.melin.superior.common.relational.create.CreateNamespace
import io.github.melin.superior.common.relational.drop.DropNamespace
import io.github.melin.superior.common.relational.namespace.UseNamespace
import io.github.melin.superior.common.relational.table.ColumnRel
import io.github.melin.superior.common.relational.create.CreateTable
import io.github.melin.superior.common.relational.dml.*
import io.github.melin.superior.common.relational.drop.DropIndex
import io.github.melin.superior.common.relational.drop.DropTable
import io.github.melin.superior.common.relational.table.TruncateTable
import io.github.melin.superior.parser.mysql.antlr4.MySqlParser
import io.github.melin.superior.parser.mysql.antlr4.MySqlParserBaseVisitor
import org.antlr.v4.runtime.tree.TerminalNodeImpl

/**
 *
 * Created by libinsong on 2018/2/8.
 */
class MySQLAntlr4Visitor : MySqlParserBaseVisitor<StatementData>() {

    private var currentOptType: StatementType = StatementType.UNKOWN
    private var limit: Int? = null
    private val primaryKeys = ArrayList<String>()

    private val inputTables: ArrayList<TableId> = arrayListOf()

    //-----------------------------------database-------------------------------------------------

    override fun visitCreateDatabase(ctx: MySqlParser.CreateDatabaseContext): StatementData {
        val databaseName = ctx.uid().text
        val createNamespace = CreateNamespace(databaseName)
        return StatementData(StatementType.CREATE_NAMESPACE, createNamespace)
    }

    override fun visitDropDatabase(ctx: MySqlParser.DropDatabaseContext): StatementData {
        val databaseName = ctx.uid().text
        val dropNamespace = DropNamespace(databaseName)
        return StatementData(StatementType.DROP_NAMESPACE, dropNamespace)
    }

    //-----------------------------------table-------------------------------------------------

    override fun visitColumnCreateTable(ctx: MySqlParser.ColumnCreateTableContext): StatementData {
        val tableId = parseFullId(ctx.tableName().fullId())
        var comment: String? = null
        ctx.tableOption().forEach {
            when(it) {
                is MySqlParser.TableOptionCommentContext -> {
                    comment = StringUtil.cleanQuote(it.STRING_LITERAL().text)
                }
            }
        }
        val columnRels = ArrayList<ColumnRel>()
        val properties = HashMap<String, String>()

        ctx.createDefinitions().children.forEach { column ->
            if(column is MySqlParser.ColumnDeclarationContext ) {
                val name = StringUtil.cleanBackQuote(column.fullColumnName().text)


                var dataType = column.columnDefinition().dataType().getChild(0).text.lowercase()
                val count = column.columnDefinition().dataType().childCount
                if(count > 1) {
                    val item = column.columnDefinition().dataType().getChild(1)
                    if(item is MySqlParser.LengthOneDimensionContext ||
                            item is MySqlParser.LengthTwoDimensionContext ||
                            item is MySqlParser.LengthTwoOptionalDimensionContext) {
                        dataType = dataType + item.text
                    }
                }

                var colComment:String? = null
                column.columnDefinition().columnConstraint().forEach {
                    if(it is MySqlParser.CommentColumnConstraintContext) {
                        colComment = StringUtil.cleanQuote(it.STRING_LITERAL().text)
                    }
                }
                columnRels.add(ColumnRel(name, dataType, colComment))
            }
        }

        super.visitColumnCreateTable(ctx)

        val ifNotExists: Boolean = if (ctx.ifNotExists() != null) true else false
        columnRels.forEach { columnRel: ColumnRel -> if (primaryKeys.contains(columnRel.name)) { columnRel.isPk = true } }
        val createTable = CreateTable(tableId, comment,
                null, null, columnRels, properties, null, ifNotExists)

        if (ctx.partitionDefinitions() != null) {
            createTable.partitionType = "PARTITION"
        }

        return StatementData(StatementType.CREATE_TABLE, createTable)
    }

    override fun visitPrimaryKeyTableConstraint(ctx: MySqlParser.PrimaryKeyTableConstraintContext): StatementData? {
        val count = ctx.indexColumnNames().childCount

        for (i in 1..(count-2)) {
            var column = ctx.indexColumnNames().getChild(i).text
            column = StringUtil.cleanBackQuote(column)
            primaryKeys.add(column)
        }

        return null
    }

    override fun visitDropTable(ctx: MySqlParser.DropTableContext): StatementData {
        if(ctx.tables().tableName().size > 1) {
            throw SQLParserException("不支持drop多个表")
        }
        val tableId = parseFullId(ctx.tables().tableName(0).fullId())

        val dropTable = DropTable(tableId)
        dropTable.ifExists = if (ctx.ifExists() != null) true else false
        return StatementData(StatementType.DROP_TABLE, dropTable)
    }

    override fun visitTruncateTable(ctx: MySqlParser.TruncateTableContext): StatementData {
        val tableId = parseFullId(ctx.tableName().fullId())
        val truncateTable = TruncateTable(tableId)
        return StatementData(StatementType.TRUNCATE_TABLE, truncateTable)
    }

    override fun visitRenameTable(ctx: MySqlParser.RenameTableContext): StatementData {
        val tableId = parseFullId(ctx.renameTableClause().get(0).tableName(0).fullId())
        val newTableId = parseFullId(ctx.renameTableClause().get(0).tableName(1).fullId())

        val action = AlterTableAction(newTableId)
        val alterTable = AlterTable(AlterType.RENAME_TABLE, tableId, action)
        return StatementData(StatementType.ALTER_TABLE, alterTable)
    }

    override fun visitUseStatement(ctx: MySqlParser.UseStatementContext): StatementData {
        val databaseName = ctx.uid().text
        val data = UseNamespace(databaseName)
        return StatementData(StatementType.USE, data)
    }

    //-----------------------------------Alter-----------------------------------------------

    override fun visitAlterTable(ctx: MySqlParser.AlterTableContext): StatementData? {
        if(ctx.childCount > 4) {
            throw SQLParserException("不允许同时执行多个alter")
        }
        val statement = ctx.getChild(3)
        val tableId = parseFullId(ctx.tableName().fullId())
        if (statement is MySqlParser.AlterByChangeColumnContext) {
            val columnName = StringUtil.cleanBackQuote(statement.oldColumn.text)
            val newColumnName = StringUtil.cleanBackQuote(statement.newColumn.text)
            val dataType = statement.columnDefinition().dataType().text
            var comment:String? = null

            statement.columnDefinition().children.forEach {
                if(it is MySqlParser.CommentColumnConstraintContext) {
                    comment = StringUtil.cleanQuote(it.STRING_LITERAL().text)
                }
            }

            val action = AlterColumnAction(columnName, dataType, comment)
            action.newColumName = newColumnName

            val alterTable = AlterTable(AlterType.ALTER_COLUMN, tableId, action)
            return StatementData(StatementType.ALTER_TABLE, alterTable)
        } else if(statement is MySqlParser.AlterByAddColumnContext) {
            val columnName = StringUtil.cleanBackQuote(statement.uid().get(0).text)
            val dataType = statement.columnDefinition().dataType().text
            var comment:String? = null
            statement.columnDefinition().children.forEach {
                if(it is MySqlParser.CommentColumnConstraintContext) {
                    comment = StringUtil.cleanQuote(it.STRING_LITERAL().text)
                }
            }

            val action = AlterColumnAction(columnName, dataType, comment)
            val alterTable = AlterTable(AlterType.ADD_COLUMN, tableId, action)
            return StatementData(StatementType.ALTER_TABLE, alterTable)
        } else if(statement is MySqlParser.AlterByDropColumnContext) {
            val columnName = StringUtil.cleanBackQuote(statement.uid().text)
            val action = DropColumnAction(columnName)
            val alterTable = AlterTable(AlterType.DROP_COLUMN, tableId, action)
            return StatementData(StatementType.ALTER_TABLE, alterTable)
        } else if(statement is MySqlParser.AlterByModifyColumnContext) {
            val columnName = StringUtil.cleanBackQuote(statement.uid().get(0).text)
            val dataType = statement.columnDefinition().dataType().text

            val action = AlterColumnAction(columnName, dataType)
            val alterTable = AlterTable(AlterType.ALTER_COLUMN, tableId, action)
            return StatementData(StatementType.ALTER_TABLE, alterTable)
        } else if(statement is MySqlParser.AlterByAddIndexContext) {
            val createIndex = CreateIndex(statement.uid().text)
            val alterTable = AlterTable(AlterType.ADD_INDEX, tableId, createIndex)
            return StatementData(StatementType.ALTER_TABLE, alterTable)
        } else if(statement is MySqlParser.AlterByDropIndexContext) {
            val dropIndex = DropIndex(statement.uid().text)
            val alterTable = AlterTable(AlterType.DROP_INDEX, tableId, dropIndex)
            return StatementData(StatementType.ALTER_TABLE, alterTable)
        } else if(statement is MySqlParser.AlterByAddPrimaryKeyContext) {
            val action = AlterTableAction()
            val alterTable = AlterTable(AlterType.ADD_PRIMARY_KEY, tableId, action)
            return StatementData(StatementType.ALTER_TABLE, alterTable)
        } else if(statement is MySqlParser.AlterByAddUniqueKeyContext) {
            val action = AlterTableAction()
            val alterTable = AlterTable(AlterType.ADD_UNIQUE_KEY, tableId, action)
            return StatementData(StatementType.ALTER_TABLE, alterTable)
        } else if(statement is MySqlParser.AlterByAddPartitionContext) {
            val action = AlterTableAction()
            val alterTable = AlterTable(AlterType.ADD_PARTITION, tableId, action)
            return StatementData(StatementType.ALTER_TABLE, alterTable)
        } else if(statement is MySqlParser.AlterByDropPartitionContext) {
            val action = AlterTableAction()
            val alterTable = AlterTable(AlterType.DROP_PARTITION, tableId, action)
            return StatementData(StatementType.ALTER_TABLE, alterTable)
        } else if(statement is MySqlParser.AlterByTruncatePartitionContext) {
            val action = AlterTableAction()
            val alterTable = AlterTable(AlterType.TRUNCATE_PARTITION, tableId, action)
            return StatementData(StatementType.ALTER_TABLE, alterTable)
        }

        return super.visitAlterTable(ctx)
    }

    override fun visitAnalyzeTable(ctx: MySqlParser.AnalyzeTableContext): StatementData {
        val tables = ArrayList<TableId>()
        ctx.tables().tableName().forEach { context ->
            val tableId = parseFullId(context.fullId())
            tables.add(tableId)
        }

        val analyze = AnalyzeTable(tables)
        return StatementData(StatementType.ANALYZE_TABLE, analyze)
    }

    //-----------------------------------DML-------------------------------------------------

    override fun visitDmlStatement(ctx: MySqlParser.DmlStatementContext): StatementData {
        if (ctx.selectStatement() != null) {
            currentOptType = StatementType.SELECT
            super.visitDmlStatement(ctx)

            val queryStmt = QueryStmt(inputTables, limit)
            return StatementData(StatementType.SELECT, queryStmt)
        }

        return super.visitDmlStatement(ctx);
    }

    override fun visitInsertStatement(ctx: MySqlParser.InsertStatementContext): StatementData {
        val tableId = parseFullId(ctx.tableName().fullId())

        return if (ctx.insertStatementValue().selectStatement() != null) {
            currentOptType = StatementType.INSERT_SELECT
            super.visit(ctx.insertStatementValue().selectStatement())

            val insertStmt = InsertStmt(InsertMode.INTO, tableId)
            insertStmt.inputTables = inputTables
            StatementData(StatementType.INSERT_SELECT, insertStmt)
        } else {
            currentOptType = StatementType.INSERT_VALUES
            val insertStmt = InsertStmt(InsertMode.INTO, tableId)
            StatementData(StatementType.INSERT_VALUES, insertStmt)
        }
    }

    override fun visitReplaceStatement(ctx: MySqlParser.ReplaceStatementContext): StatementData {
        val tableId = parseFullId(ctx.tableName().fullId())

        return if (ctx.insertStatementValue().selectStatement() != null) {
            currentOptType = StatementType.INSERT_SELECT
            super.visit(ctx.insertStatementValue().selectStatement())

            val insertStmt = InsertStmt(InsertMode.INTO, tableId)
            insertStmt.inputTables = inputTables
            insertStmt.mysqlReplace = true
            StatementData(StatementType.INSERT_SELECT, insertStmt)
        } else {
            currentOptType = StatementType.INSERT_VALUES
            val insertStmt = InsertStmt(InsertMode.INTO, tableId)
            insertStmt.mysqlReplace = true
            StatementData(StatementType.INSERT_VALUES, insertStmt)
        }
    }

    override fun visitDeleteStatement(ctx: MySqlParser.DeleteStatementContext): StatementData {
        currentOptType = StatementType.DELETE

        val deleteTable = if (ctx.multipleDeleteStatement() != null) {
            this.visit(ctx.multipleDeleteStatement().expression())

            val outputTables = ctx.multipleDeleteStatement().tableName().map { parseFullId(it.fullId()) }
            val deleteTable = DeleteTable(inputTables)
            super.visitTableSources(ctx.multipleDeleteStatement().tableSources())
            deleteTable.outputTables = outputTables
            deleteTable
        } else {
            this.visit(ctx.singleDeleteStatement().expression())

            val tableId = parseFullId(ctx.singleDeleteStatement().tableName().fullId())
            DeleteTable(tableId, inputTables)
        }

        return StatementData(StatementType.DELETE, deleteTable)
    }

    override fun visitUpdateStatement(ctx: MySqlParser.UpdateStatementContext): StatementData {
        currentOptType = StatementType.UPDATE
        val updateTable = if (ctx.multipleUpdateStatement() != null) {
            val updateTable = UpdateTable(inputTables.toMutableList())
            this.visit(ctx.multipleUpdateStatement().expression())
            inputTables.clear()
            super.visitTableSources(ctx.multipleUpdateStatement().tableSources())
            updateTable.outputTables = inputTables
            updateTable
        } else {
            this.visit(ctx.singleUpdateStatement().expression())
            val tableId = parseFullId(ctx.singleUpdateStatement().tableName().fullId())
            UpdateTable(tableId, inputTables)
        }

        return StatementData(StatementType.UPDATE, updateTable)
    }

    override fun visitCreateIndex(ctx: MySqlParser.CreateIndexContext): StatementData {
        val tableId = parseFullId(ctx.tableName().fullId())
        val createIndex = CreateIndex(ctx.uid().text)
        val alterTable = AlterTable(AlterType.ADD_INDEX, tableId, createIndex)
        return StatementData(StatementType.ALTER_TABLE, alterTable)
    }

    override fun visitDropIndex(ctx: MySqlParser.DropIndexContext): StatementData {
        val tableId = parseFullId(ctx.tableName().fullId())
        val dropIndex = DropIndex(ctx.uid().text)
        val alterTable = AlterTable(AlterType.DROP_INDEX, tableId, dropIndex)
        return StatementData(StatementType.ALTER_TABLE, alterTable)
    }

    //-----------------------------------private method-------------------------------------------------

    override fun visitTableName(ctx: MySqlParser.TableNameContext): StatementData? {
        if(StatementType.SELECT == currentOptType ||
            StatementType.INSERT_SELECT == currentOptType ||
            StatementType.UPDATE == currentOptType ||
            StatementType.DELETE == currentOptType) {

            val tableId = parseFullId(ctx.fullId())
            inputTables.add(tableId)
        }
        return null
    }

    override fun visitLimitClause(ctx: MySqlParser.LimitClauseContext): StatementData? {
        if (currentOptType == StatementType.SELECT ) {
            limit = ctx.limit.text.toInt()
        }
        return null
    }

    private fun parseFullId(fullId: MySqlParser.FullIdContext): TableId {
        var databaseName: String? = null
        var tableName = ""

        if (fullId.childCount == 2) {
            databaseName = fullId.uid().get(0).text
            tableName = (fullId.getChild(1) as TerminalNodeImpl).text.substring(1)
        } else if(fullId.childCount == 3) {
            databaseName = StringUtil.cleanBackQuote(fullId.uid().get(0).text)
            tableName = StringUtil.cleanBackQuote((fullId.getChild(2) as MySqlParser.UidContext).text)
        } else {
            tableName = fullId.uid().get(0).text
        }

        if (databaseName != null) {
            databaseName = StringUtil.cleanBackQuote(databaseName)
        }
        tableName = StringUtil.cleanBackQuote(tableName)

        return TableId(databaseName, tableName);
    }
}
