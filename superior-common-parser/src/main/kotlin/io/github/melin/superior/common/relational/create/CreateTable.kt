package io.github.melin.superior.common.relational.create

import io.github.melin.superior.common.PrivilegeType
import io.github.melin.superior.common.SqlType
import io.github.melin.superior.common.StatementType
import io.github.melin.superior.common.relational.abs.AbsTableStatement
import io.github.melin.superior.common.relational.TableId
import io.github.melin.superior.common.relational.table.ColumnRel

data class CreateTable(
    override val tableId: TableId,
    val comment: String? = null,
    var lifeCycle: Int? = null,
    var partitionColumnRels: List<ColumnRel>? = null,
    var columnRels: List<ColumnRel>? = null,
    var properties: Map<String, String>? = null,
    var fileFormat: String? = null,
    var ifNotExists: Boolean = false, //是否存在 if not exists 关键字
    var external: Boolean = false,
    var temporary: Boolean = false,
    var location: String? = null,
    var querySql: String? = null,
    var partitionColumnNames: List<String>? = null
) : AbsTableStatement() { //是否存在 if exists 关键字
    override val statementType = StatementType.CREATE_TABLE
    override val privilegeType = PrivilegeType.CREATE
    override val sqlType = SqlType.DDL

    // 建表方式：hive & spark. https://spark.apache.org/docs/3.2.0/sql-ref-syntax-ddl-create-table.html
    var replace = false
    var tableType: String = "hive"
    var partitionType: String? = null // 分区类型

    constructor(tableId: TableId, comment: String?, columnRels: List<ColumnRel>?):
            this(tableId, comment, null, null, columnRels, null, null, false)

    constructor(tableId: TableId, comment: String?, lifeCycle: Int?, columnRels: List<ColumnRel>?):
            this(tableId, comment, lifeCycle, null, columnRels, null, null, false)

    constructor(
        tableId: TableId,
        comment: String? = null,
        columnRels: List<ColumnRel>? = null,
        ifNotExists: Boolean,
        properties: Map<String, String>? = null):
            this(tableId, comment, null, null, columnRels, properties, null, ifNotExists)
}