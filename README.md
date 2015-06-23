avaje-ebeanorm
==============

Main EbeanORM artifact 3.3.4

Important notice
==============
For my internal use only. Diff is only in ddl packge.
Create statements for Tables and Sequences appended by 'if not exists' clause.
was:
    ctx.write("create table ");
    ctx.write("create sequence ");
now: 
    ctx.write("create table if not exists ");
    ctx.write("create sequence if not exists ");

Affected classes: 
    com.avaje.ebeaninternal.server.ddl.CreateTableVisitor
    com.avaje.ebeaninternal.server.ddl.CreateSequenceVisitor

