#!/usr/bin/env python3
# ============================================================
# FailureRepo - 失败经验库（SQLite 持久化）
# ============================================================
# 职责：
#   1. 记录所有失败事件（死亡坐标、死亡原因、当前任务）
#   2. 查询指定坐标附近的死亡记录（用于 avoid_zone 标记）
#   3. 统计分析失败模式（如某区域高频死亡）
#
# 数据库结构：
#   failures 表：
#     id         INTEGER PRIMARY KEY
#     x          REAL  死亡 X 坐标
#     y          REAL  死亡 Y 坐标
#     z          REAL  死亡 Z 坐标
#     dimension  TEXT  维度 (overworld / nether / end)
#     cause      TEXT  死亡原因
#     task       TEXT  死亡时正在执行的任务
#     timestamp  INTEGER Unix 时间戳
# ============================================================

import sqlite3
import logging
import math
import time
from typing import List, Dict, Optional

logger = logging.getLogger("EasyAI/FailureRepo")


class FailureRepo:
    """失败经验库 - SQLite 读写"""

    def __init__(self, db_path: str):
        self.db_path = db_path
        self._conn: Optional[sqlite3.Connection] = None
        self._init_db()
        logger.info(f"失败经验库已加载: {db_path}")

    # ============================================================
    # 初始化数据库
    # ============================================================
    def _init_db(self):
        """创建表结构（如果不存在）"""
        self._conn = sqlite3.connect(self.db_path, check_same_thread=False)
        self._conn.execute("""
            CREATE TABLE IF NOT EXISTS failures (
                id         INTEGER PRIMARY KEY AUTOINCREMENT,
                x          REAL    NOT NULL,
                y          REAL    NOT NULL,
                z          REAL    NOT NULL,
                dimension  TEXT    DEFAULT 'overworld',
                cause      TEXT    DEFAULT 'unknown',
                task       TEXT    DEFAULT '',
                timestamp  INTEGER NOT NULL
            )
        """)
        # 创建索引以加速空间查询
        self._conn.execute("""
            CREATE INDEX IF NOT EXISTS idx_failures_xyz
            ON failures(x, z)
        """)
        self._conn.commit()

    # ============================================================
    # 记录失败事件
    # ============================================================
    def record_failure(
        self,
        x: float,
        y: float,
        z: float,
        cause: str = "unknown",
        task: str = "",
        dimension: str = "overworld",
    ):
        """
        记录一次失败事件

        Args:
            x, y, z: 死亡坐标
            cause: 死亡原因（如 "僵尸击杀", "掉落", "岩浆"）
            task: 死亡时正在执行的任务描述
            dimension: 维度
        """
        try:
            self._conn.execute(
                """INSERT INTO failures (x, y, z, dimension, cause, task, timestamp)
                   VALUES (?, ?, ?, ?, ?, ?, ?)""",
                (x, y, z, dimension, cause, task, int(time.time())),
            )
            self._conn.commit()
            logger.warning(
                f"记录失败: ({x}, {y}, {z}) cause={cause} task={task}"
            )
        except Exception as e:
            logger.error(f"记录失败事件异常: {e}")

    # ============================================================
    # 查询附近死亡记录
    # ============================================================
    def get_nearby_failures(
        self, x: float, z: float, radius: float = 20.0
    ) -> List[Dict]:
        """
        查询指定坐标附近（给定半径内）的死亡记录

        Args:
            x, z: 查询中心坐标
            radius: 搜索半径（方块数）

        Returns:
            死亡记录列表，每条包含 id, x, y, z, cause, task, timestamp
        """
        try:
            # 使用粗筛 + 精筛的两步查询
            # 先用索引粗筛（正方形范围），再用距离精筛（圆形范围）
            cursor = self._conn.execute(
                """SELECT id, x, y, z, dimension, cause, task, timestamp
                   FROM failures
                   WHERE x BETWEEN ? AND ?
                     AND z BETWEEN ? AND ?""",
                (x - radius, x + radius, z - radius, z + radius),
            )
            rows = cursor.fetchall()

            # 精筛：计算实际距离
            nearby = []
            for row in rows:
                row_x, row_z = row[1], row[3]
                dist = math.sqrt((row_x - x) ** 2 + (row_z - z) ** 2)
                if dist <= radius:
                    nearby.append({
                        "id": row[0],
                        "x": row[1],
                        "y": row[2],
                        "z": row[3],
                        "dimension": row[4],
                        "cause": row[5],
                        "task": row[6],
                        "timestamp": row[7],
                        "distance": round(dist, 1),
                    })

            return nearby

        except Exception as e:
            logger.error(f"查询附近失败记录异常: {e}")
            return []

    # ============================================================
    # 获取所有失败记录
    # ============================================================
    def get_all_failures(self, limit: int = 100) -> List[Dict]:
        """获取最近的失败记录"""
        try:
            cursor = self._conn.execute(
                """SELECT id, x, y, z, dimension, cause, task, timestamp
                   FROM failures
                   ORDER BY timestamp DESC
                   LIMIT ?""",
                (limit,),
            )
            rows = cursor.fetchall()
            return [
                {
                    "id": r[0],
                    "x": r[1],
                    "y": r[2],
                    "z": r[3],
                    "dimension": r[4],
                    "cause": r[5],
                    "task": r[6],
                    "timestamp": r[7],
                }
                for r in rows
            ]
        except Exception as e:
            logger.error(f"获取失败记录异常: {e}")
            return []

    # ============================================================
    # 统计信息
    # ============================================================
    def get_stats(self) -> dict:
        """获取失败统计信息"""
        try:
            cursor = self._conn.execute("SELECT COUNT(*) FROM failures")
            total = cursor.fetchone()[0]

            cursor = self._conn.execute(
                """SELECT cause, COUNT(*) as cnt
                   FROM failures
                   GROUP BY cause
                   ORDER BY cnt DESC
                   LIMIT 5"""
            )
            top_causes = cursor.fetchall()

            return {
                "total_deaths": total,
                "top_causes": [
                    {"cause": c, "count": n} for c, n in top_causes
                ],
            }
        except Exception as e:
            logger.error(f"获取统计信息异常: {e}")
            return {"total_deaths": 0, "top_causes": []}

    # ============================================================
    # 关闭数据库
    # ============================================================
    def close(self):
        if self._conn:
            self._conn.close()
            self._conn = None
            logger.info("失败经验库已关闭")
