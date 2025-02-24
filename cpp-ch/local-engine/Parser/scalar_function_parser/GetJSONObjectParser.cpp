/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
#include <Parser/FunctionParser.h>
#include <Poco/Logger.h>
#include <Common/Exception.h>
#include <Common/logger_useful.h>

namespace DB
{
namespace ErrorCodes
{
    extern const int BAD_ARGUMENTS;
}
}

namespace local_engine
{

class GetJSONObjectParser : public FunctionParser
{
public:
    static constexpr auto name = "get_json_object";

    explicit GetJSONObjectParser(SerializedPlanParser * plan_parser_): FunctionParser(plan_parser_) {}
    ~GetJSONObjectParser() override = default;

    String getName() const override { return name; }
protected:
    String getCHFunctionName(const substrait::Expression_ScalarFunction &) const override
    {
        return name;
    }

    DB::ActionsDAG::NodeRawConstPtrs parseFunctionArguments(
        const substrait::Expression_ScalarFunction & substrait_func,
        const String & ch_func_name,
        DB::ActionsDAGPtr & actions_dag) const override
    {
        const auto & args = substrait_func.arguments();
        if (args.size() != 2)
        {
            throw DB::Exception(DB::ErrorCodes::BAD_ARGUMENTS, "Function {} requires 2 arguments", ch_func_name);
        }

        const auto * json_column_node = parseExpression(actions_dag, args[0].value());
        auto decoded_json_col_name = getDecodedJSONColumnName(json_column_node->result_name);
        const auto * decoded_json_column_node = actions_dag->tryFindInOutputs(decoded_json_col_name);
        if (!decoded_json_column_node)
        {
            // This extra-column should be removed later.
            decoded_json_column_node = toFunctionNode(actions_dag, "decodeJSONString", decoded_json_col_name, {json_column_node});
            actions_dag->addOrReplaceInOutputs(*decoded_json_column_node);
            assert(decoded_json_column_node);
        }
        const auto * json_path_node = parseExpression(actions_dag, args[1].value());
        return {decoded_json_column_node, json_path_node};
    }

private:
    String getDecodedJSONColumnName(const String & json_column_name) const
    {
        return "Decode(" + json_column_name + ")";
    }
};

static FunctionParserRegister<GetJSONObjectParser> register_get_json_object_parser;
}
