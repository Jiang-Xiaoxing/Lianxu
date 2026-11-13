package app.lianxu.independent

/** One contract for appraisal, final replies and proactive decisions; not a semantic classifier. */
internal object RoleConsistency {
    val RULES="""
        [人设与成长共同规则]
        人设是起点，不能把整段人设都当成永不变化的指令。姓名、身份背景和明确的核心原则保持稳定；普通兴趣、喜恶、表达方式、相处习惯及关系起点是初始倾向，不因写在人设里就成为不可侵犯的底线。不把普通偏好升级为核心原则；不确定属于哪类时保留不确定，必要时澄清，不自动改写身份或原则。
        从相处中学到的变化，只能是有来源、适用范围明确的看法（viewpoint）、兴趣（interest）或习惯（habit）；每个编号（ID）只用当前有效的版本。已经确定的变化（active）可以在相应情境下修正最初的倾向；尚未确定的想法（candidate）和还在尝试的变化（developing）都可以修正，不能当成已经确定的变化；已暂停的变化（paused）和来源已撤回的内容都不能用。以前的评价和早期版本，不代表现在的状态。
        当前明确请求在本次相处方式上优先于旧习惯，但一句临时要求不等于永久改变偏好，不要求角色放弃核心原则，也不授予工具或后台权限。角色可以有不同意见；变化不以迎合为目标。
        新旧内容不一致时先核对人物、时间、适用情境、来源和状态，不机械地以最新、最常出现或写在人设中判胜负。同范围仍有未解决矛盾时不要把任一条说成定论，使用可用来源核实或自然澄清，不以旧设定压过有依据的成长。角色虚构背景、用户资料、真实共同经历和模型推测分别对待，不把它们相互替代。
    """.trimIndent()
    const val GENERATION_GUIDANCE="将稳定的身份背景和明确核心原则，与可成长的初始兴趣、表达方式、相处习惯区分写清。普通喜恶不能写成不可侵犯的底线；核心原则不能反过来把每种相处方法永远锁死，给真实交流后的有依据变化留出空间。"

    // A retry must not reuse an appraisal produced under a different consistency contract.
    fun appraisalStamp(profile:String)=stableDigest(RULES+"\u0000"+profile)

    /** Shared by wire parsing and final acceptance; never infer semantic scope from a string. */
    fun learningUseError(considered:List<String>,used:List<String>,judgment:String,learning:List<RoleLearningState>?):String? {
        if(considered.distinct().size!=considered.size||used.distinct().size!=used.size)return "DUPLICATE_LEARNING"
        if(!considered.containsAll(used))return "LEARNING_NOT_CONSIDERED"
        if(judgment !in setOf("","evidence_based","inference","uncertain"))return "INVALID_judgmentKind"
        if(used.isNotEmpty()&&judgment.isEmpty())return "LEARNING_USE_NEEDS_JUDGMENT_KIND"
        if(considered.isEmpty())return null
        if(learning==null)return "LEARNING_CONTEXT_REQUIRED"
        if(considered.any{id->learning.count{it.id==id}!=1})return "UNKNOWN_LEARNING"
        for(id in used){val value=learning.single{it.id==id}
            if(value.kind !in setOf("viewpoint","interest","habit"))return "INVALID_LEARNING_KIND"
            if(value.state !in setOf("candidate","developing","active","paused"))return "INVALID_LEARNING_STATE"
            if(value.state=="paused")return "PAUSED_LEARNING"
            if(value.state!="active"&&judgment=="evidence_based")return "CANDIDATE_IS_NOT_ESTABLISHED"
        }
        return null
    }
}
